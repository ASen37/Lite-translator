package com.example.test.translator.ui;

import com.example.test.translator.service.CredentialStore;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.io.IOException;

/**
 * 设置窗口：配置翻译引擎凭证（百度 APPID + 密钥），保存后加密落盘。
 */
public class SettingsView {

    private static final double WIDTH = 320;
    private static final double HEIGHT = 220;

    private final Stage owner;
    private final CredentialStore credentialStore;
    private final Runnable onSaved;

    public SettingsView(Stage owner, CredentialStore credentialStore, Runnable onSaved) {
        this.owner = owner;
        this.credentialStore = credentialStore;
        this.onSaved = onSaved;
    }

    public void show() {
        Stage stage = new Stage();
        stage.initOwner(owner);
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.initStyle(StageStyle.UTILITY);
        stage.setResizable(false);
        stage.setTitle("设置");
        stage.setWidth(WIDTH);
        stage.setHeight(HEIGHT);

        VBox box = new VBox(10);
        box.setPadding(new Insets(14));

        Label appIdLabel = new Label("百度 APPID");
        TextField appIdField = new TextField();
        appIdField.setPromptText("例如 2024010100000001");

        Label keyLabel = new Label("密钥");
        PasswordField keyField = new PasswordField();
        keyField.setPromptText("百度翻译 密钥");

        // 预填已保存的凭证
        try {
            String[] cred = credentialStore.load();
            if (cred != null) {
                appIdField.setText(cred[0]);
                keyField.setText(cred[1]);
            }
        } catch (IOException ignored) {
            // 凭证读不到就留空
        }

        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: #c0392b; -fx-font-size: 11;");
        errorLabel.setWrapText(true);

        Button saveButton = new Button("保存");
        saveButton.getStyleClass().add("flat-btn");
        saveButton.setOnAction(e -> {
            String appId = appIdField.getText().trim();
            String secretKey = keyField.getText().trim();
            if (appId.isBlank() || secretKey.isBlank()) {
                errorLabel.setText("APPID 和密钥不能为空");
                return;
            }
            try {
                credentialStore.save(appId, secretKey);
                onSaved.run();
                stage.close();
            } catch (IOException ex) {
                errorLabel.setText("保存失败：" + ex.getMessage());
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox buttonBar = new HBox(spacer, saveButton);
        buttonBar.setAlignment(Pos.CENTER_RIGHT);

        box.getChildren().addAll(appIdLabel, appIdField, keyLabel, keyField, errorLabel, buttonBar);
        stage.setScene(new Scene(box));
        stage.show();
    }
}
