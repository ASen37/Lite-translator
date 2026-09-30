package com.example.test.translator.ui;

import com.example.test.translator.model.Lang;
import com.example.test.translator.service.BaiduTranslator;
import com.example.test.translator.service.CredentialStore;
import com.example.test.translator.service.Translator;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 翻译主界面：无边框小窗，顶栏（大头针/设置/最小化）、语言选择、左右并排的输入/输出框。
 */
public class MainView {

    private static final double WIDTH = 480;
    private static final double HEIGHT = 260;
    private static final Duration DEBOUNCE_DELAY = Duration.millis(800);

    private final Stage stage;
    private final CredentialStore credentialStore = new CredentialStore();
    private final Debouncer debouncer;
    /** 翻译请求专用单线程池，避免占用 ForkJoinPool 公共线程池 */
    private final ExecutorService translatorExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "translator-worker");
        t.setDaemon(true);
        return t;
    });

    private TextArea inputArea;
    private TextArea outputArea;
    private ComboBox<Lang> fromBox;
    private ComboBox<Lang> toBox;
    private Button pinButton;
    private Button swapButton;

    private Translator translator;
    private boolean pinned;
    /** 自增序号，用于丢弃过期请求的结果 */
    private final AtomicLong requestSeq = new AtomicLong();

    public MainView(Stage stage) {
        this.stage = stage;
        this.debouncer = new Debouncer(DEBOUNCE_DELAY, this::onInputSettled);
    }

    public void show() {
        buildUi();
        reloadCredentials();
        stage.show();
    }

    private void buildUi() {
        stage.initStyle(StageStyle.TRANSPARENT);
        stage.setResizable(false);
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);

        StackPane root = new StackPane();
        root.setStyle("-fx-background-color: #f7f7f9;"
                + "-fx-background-radius: 10;"
                + "-fx-border-radius: 10;"
                + "-fx-border-color: #d8d8dc;");
        root.setPadding(new Insets(8));

        root.getChildren().add(buildContent());

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.setFill(null);
        scene.getStylesheets().add(getClass().getResource("/css/translator.css").toExternalForm());
        stage.setScene(scene);
    }

    private VBox buildContent() {
        // 先建文本区，确保语言行监听时可引用 inputArea
        VBox textAreas = buildTextAreas();

        VBox box = new VBox(6);
        box.setPadding(new Insets(0));
        box.getChildren().addAll(buildTitleBar(), buildLangBar(), textAreas);
        return box;
    }

    private HBox buildTitleBar() {
        // 标题为纯展示组件（无点击反馈），但作为窗口拖拽区域
        Label titleLabel = new Label("⇲ 翻译");
        titleLabel.getStyleClass().add("title-label");
        titleLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(titleLabel, Priority.ALWAYS);
        titleLabel.setAlignment(Pos.CENTER_LEFT);

        pinButton = new Button("📌");
        pinButton.getStyleClass().add("flat-btn");
        pinButton.setTooltip(new Tooltip("窗口置顶"));
        pinButton.setOnAction(e -> togglePinned());

        Button settingsButton = new Button("⚙");
        settingsButton.getStyleClass().add("flat-btn");
        settingsButton.setTooltip(new Tooltip("设置"));
        settingsButton.setOnAction(e -> new SettingsView(stage, credentialStore, this::reloadCredentials).show());

        Button minimizeButton = new Button("─");
        minimizeButton.getStyleClass().add("flat-btn");
        minimizeButton.setTooltip(new Tooltip("最小化到托盘"));
        minimizeButton.setOnAction(e -> stage.hide());

        HBox bar = new HBox(2, titleLabel, pinButton, settingsButton, minimizeButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        // 整个顶栏作为拖拽区域（按钮上除外），避免只能点在标题文字上才能拖动
        enableDrag(bar);
        return bar;
    }

    private HBox buildLangBar() {
        fromBox = new ComboBox<>();
        fromBox.getStyleClass().add("lang-combo");
        fromBox.getItems().addAll(Lang.AUTO, Lang.ZH, Lang.EN);
        fromBox.setValue(Lang.AUTO);

        toBox = new ComboBox<>();
        toBox.getStyleClass().add("lang-combo");
        toBox.getItems().addAll(Lang.ZH, Lang.EN);
        toBox.setValue(Lang.EN);

        // 手动源语言与目标语言相同时，自动把目标切到另一门
        fromBox.valueProperty().addListener((obs, old, val) -> {
            if (val != Lang.AUTO && val == toBox.getValue()) {
                toBox.setValue(val == Lang.ZH ? Lang.EN : Lang.ZH);
            }
            swapButton.setDisable(val == Lang.AUTO);
        });

        // 目标语言切换：若输入文本仍是源语言（非目标语言），停顿后重新翻译
        toBox.valueProperty().addListener((obs, old, val) -> {
            if (val == null) {
                return;
            }
            String text = inputArea.getText();
            if (text != null && !text.isBlank() && needsTranslation(text, val)) {
                debouncer.restart();
            }
        });

        swapButton = new Button("⇄");
        swapButton.getStyleClass().add("flat-btn");
        swapButton.setTooltip(new Tooltip("互换语言"));
        swapButton.setDisable(true);
        swapButton.setOnAction(e -> swapLanguages());

        HBox bar = new HBox(6, fromBox, swapButton, toBox);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private VBox buildTextAreas() {
        inputArea = new TextArea();
        inputArea.getStyleClass().add("translate-area");
        inputArea.setPromptText("输入或粘贴要翻译的文本…");
        inputArea.setWrapText(true);
        inputArea.textProperty().addListener((obs, old, text) -> debouncer.restart());

        // 输入框右下角的清空按钮，悬浮在文本区内部
        Button clearInputButton = new Button("清空");
        clearInputButton.getStyleClass().add("flat-btn");
        clearInputButton.setTooltip(new Tooltip("清空输入内容"));
        clearInputButton.setOnAction(e -> inputArea.clear());

        StackPane inputPane = new StackPane(inputArea, clearInputButton);
        StackPane.setAlignment(inputArea, Pos.CENTER);
        StackPane.setAlignment(clearInputButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(clearInputButton, new Insets(0, 8, 8, 0));

        outputArea = new TextArea();
        outputArea.getStyleClass().add("translate-area");
        outputArea.setWrapText(true);
        outputArea.setEditable(false);
        outputArea.setStyle("-fx-text-fill: #222;");

        // 输出框右下角的复制按钮，悬浮在文本区内部
        Button copyButton = new Button("复制");
        copyButton.getStyleClass().add("flat-btn");
        copyButton.setTooltip(new Tooltip("复制翻译结果"));
        copyButton.setOnAction(e -> copyOutput());

        StackPane outputPane = new StackPane(outputArea, copyButton);
        StackPane.setAlignment(outputArea, Pos.CENTER);
        StackPane.setAlignment(copyButton, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(copyButton, new Insets(0, 8, 8, 0));

        // 左右并排：输入在左，输出在右
        HBox areas = new HBox(8, inputPane, outputPane);
        HBox.setHgrow(inputPane, Priority.ALWAYS);
        HBox.setHgrow(outputPane, Priority.ALWAYS);

        VBox box = new VBox(areas);
        VBox.setVgrow(areas, Priority.ALWAYS);
        return box;
    }

    private void enableDrag(Region region) {
        final double[] offset = new double[2];
        final boolean[] dragging = {false};
        region.setOnMousePressed(e -> {
            // 顶栏上的按钮只响应点击，不启动窗口拖拽
            if (e.getTarget() instanceof Button) {
                dragging[0] = false;
                return;
            }
            dragging[0] = true;
            offset[0] = e.getScreenX() - stage.getX();
            offset[1] = e.getScreenY() - stage.getY();
        });
        region.setOnMouseDragged(e -> {
            if (!dragging[0]) {
                return;
            }
            stage.setX(e.getScreenX() - offset[0]);
            stage.setY(e.getScreenY() - offset[1]);
        });
        region.setOnMouseReleased(e -> dragging[0] = false);
    }

    private void togglePinned() {
        pinned = !pinned;
        stage.setAlwaysOnTop(pinned);
        if (pinned) {
            pinButton.getStyleClass().add("pinned");
        } else {
            pinButton.getStyleClass().remove("pinned");
        }
    }

    private void swapLanguages() {
        Lang from = fromBox.getValue();
        Lang to = toBox.getValue();
        fromBox.setValue(to);
        toBox.setValue(from);
    }

    private void copyOutput() {
        String text = outputArea.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** 判断输入文本是否仍是源语言（即与目标语言不同），需要翻译 */
    private boolean needsTranslation(String text, Lang target) {
        boolean containsHan = text.codePoints()
                .anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        if (target == Lang.ZH) {
            return !containsHan;   // 目标中文，但输入不含汉字 → 需要翻译
        }
        return containsHan;        // 目标英文，但输入含汉字 → 需要翻译
    }

    /** 输入停顿结束后触发：为空则清空输出，否则发起翻译 */
    private void onInputSettled() {
        String text = inputArea.getText();
        if (text == null || text.isBlank()) {
            outputArea.clear();
            return;
        }
        if (translator == null) {
            outputArea.setText("请先在设置中配置 APPID 和密钥");
            return;
        }

        Lang from = fromBox.getValue();
        Lang to = toBox.getValue();
        long seq = requestSeq.incrementAndGet();

        CompletableFuture.supplyAsync(() -> {
            try {
                return translator.translate(text, from.getCode(), to.getCode());
            } catch (Exception e) {
                return "翻译失败：" + e.getMessage();
            }
        }, translatorExecutor).thenAccept(result -> {
            // 仅当该请求仍是最新时才写入输出框
            if (seq == requestSeq.get()) {
                Platform.runLater(() -> outputArea.setText(result));
            }
        });
    }

    /** 重新读取凭证并重建翻译引擎 */
    private void reloadCredentials() {
        try {
            String[] cred = credentialStore.load();
            translator = cred != null ? new BaiduTranslator(cred[0], cred[1]) : null;
        } catch (IOException e) {
            translator = null;
            outputArea.setText("读取凭证失败：" + e.getMessage());
        }
    }
}
