package com.example.test.translator;

import com.example.test.translator.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.control.Button;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.awt.AWTException;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;

/**
 * 翻译程序入口：创建无边框主窗口，并注册系统托盘（最小化/退出）。
 * 托盘右键菜单为 JavaFX 自绘，避免 AWT 菜单中文乱码。
 */
public class TranslatorMain extends Application {

    /** 托盘菜单尺寸 */
    private static final double MENU_WIDTH = 140;
    private static final double MENU_HEIGHT = 64;

    private Stage primaryStage;
    /** 托盘菜单的常驻 owner：透明不可见的 1x1 工具窗口，保证主窗口隐藏时 Popup 仍可显示 */
    private Stage trayOwnerStage;
    private TrayIcon trayIcon;
    /** 程序自绘图标（蓝底白「译」），不依赖怪兽项目资源 */
    private BufferedImage appIcon;

    /** 当前托盘菜单 */
    private Popup trayMenuPopup;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;

        // 窗口隐藏后（最小化到托盘）不退出 JVM
        Platform.setImplicitExit(false);

        appIcon = createAppIcon();
        primaryStage.getIcons().add(toFxImage(appIcon));

        createTrayOwnerStage();
        new MainView(primaryStage).show();
        setupTray();
    }

    private void setupTray() {
        if (!SystemTray.isSupported()) {
            return;
        }
        try {
            trayIcon = new TrayIcon(appIcon, "翻译", null);
            trayIcon.setImageAutoSize(true);
            // 单击恢复窗口
            trayIcon.addActionListener(e -> showWindow());
            // 右键弹出 JavaFX 自绘菜单（避免 AWT 菜单中文乱码）
            trayIcon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    // TrayIcon 事件的屏幕坐标不可靠（常返回 0,0），改用鼠标实时位置
                    Point p = MouseInfo.getPointerInfo().getLocation();
                    if (e.getButton() == MouseEvent.BUTTON3) {
                        showTrayMenu(p.x, p.y);
                    } else if (e.getClickCount() == 2) {
                        showWindow();
                    }
                }
            });
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            System.err.println("托盘初始化失败: " + e.getMessage());
        }
    }

    /**
     * 创建一个常驻显示但不可见的 1x1 工具窗口，作为托盘菜单的 owner。
     * JavaFX Popup 要求 owner 的 root window 处于 showing 状态，否则 show 会静默失败；
     * 主窗口最小化到托盘后 primaryStage 是 hidden，因此需要这个透明窗口兜底。
     */
    private void createTrayOwnerStage() {
        trayOwnerStage = new Stage();
        trayOwnerStage.initStyle(StageStyle.UTILITY);
        trayOwnerStage.setWidth(1);
        trayOwnerStage.setHeight(1);
        trayOwnerStage.setOpacity(0);
        // 放到屏幕外，避免这个透明窗口挡住屏幕边缘像素或截获鼠标点击
        trayOwnerStage.setX(-1000);
        trayOwnerStage.setY(-1000);
        trayOwnerStage.show();
    }

    /** 在鼠标位置显示托盘右键菜单：向上弹出、不进任务栏、点击外部自动隐藏 */
    private void showTrayMenu(double x, double y) {
        Platform.runLater(() -> {
            // 先收起已显示的菜单，避免连续右键时叠加多个 Popup
            hideTrayMenu();

            // 找到鼠标所在屏幕（托盘在任务栏里，visualBounds 不含任务栏，因此用完整 bounds 判断）
            Screen screen = Screen.getPrimary();
            for (Screen s : Screen.getScreens()) {
                if (s.getBounds().contains(x, y)) {
                    screen = s;
                    break;
                }
            }
            Rectangle2D vb = screen.getVisualBounds();

            // 菜单向上弹出（托盘在屏幕底部，从鼠标处往上长），并钳制在屏幕内
            double menuX = Math.max(x, vb.getMinX());
            double menuY = y - MENU_HEIGHT;
            if (menuX + MENU_WIDTH > vb.getMaxX()) {
                menuX = vb.getMaxX() - MENU_WIDTH;
            }
            if (menuY < vb.getMinY()) {
                menuY = vb.getMinY();
            }

            // Popup 不进任务栏；autoHide 会在鼠标点击菜单外部/其他程序/任务栏时自动收起
            Popup popup = new Popup();
            popup.setAutoFix(true);
            popup.setAutoHide(true);

            Button showButton = trayMenuItem("显示窗口", () -> {
                hideTrayMenu();
                showWindow();
            });
            Button exitButton = trayMenuItem("退出", () -> {
                hideTrayMenu();
                Platform.exit();
            });

            VBox box = new VBox(showButton, exitButton);
            box.setStyle("-fx-background-color: #ffffff;"
                    + "-fx-background-radius: 8;"
                    + "-fx-border-radius: 8;"
                    + "-fx-border-color: #d8d8dc;");

            popup.getContent().add(box);
            trayMenuPopup = popup;

            // JavaFX Popup 的 autoHide 依赖 owner 窗口持有焦点（focus grab），
            // 所以先让不可见的工具窗口获得焦点，再显示 Popup。
            trayOwnerStage.toFront();
            trayOwnerStage.requestFocus();
            popup.show(trayOwnerStage, menuX, menuY);
        });
    }

    /** 收起托盘菜单 */
    private void hideTrayMenu() {
        if (trayMenuPopup != null) {
            trayMenuPopup.hide();
            trayMenuPopup = null;
        }
    }

    private Button trayMenuItem(String text, Runnable action) {
        Button item = new Button(text);
        item.setMaxWidth(Double.MAX_VALUE);
        item.setAlignment(Pos.CENTER_LEFT);
        item.setStyle("-fx-background-color: transparent;"
                + "-fx-background-radius: 0;"
                + "-fx-padding: 6 10;"
                + "-fx-font-family: \"Microsoft YaHei\";"
                + "-fx-font-size: 12;"
                + "-fx-text-fill: #222;");
        // hover 高亮
        item.hoverProperty().addListener((obs, old, hovering) -> {
            if (hovering) {
                item.setStyle(item.getStyle().replace("-fx-background-color: transparent;",
                        "-fx-background-color: #e8f0fe;"));
            } else {
                item.setStyle(item.getStyle().replace("-fx-background-color: #e8f0fe;",
                        "-fx-background-color: transparent;"));
            }
        });
        item.setOnAction(e -> action.run());
        return item;
    }

    private void showWindow() {
        Platform.runLater(() -> {
            primaryStage.show();
            primaryStage.toFront();
            primaryStage.requestFocus();
        });
    }

    /**
     * 将 AWT BufferedImage 转为 JavaFX Image。
     * 直接写入像素，避免引入 javafx-swing 依赖，减少运行时类加载与内存占用。
     */
    private static Image toFxImage(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        WritableImage image = new WritableImage(width, height);
        int[] pixels = new int[width * height];
        source.getRGB(0, 0, width, height, pixels, 0, width);
        image.getPixelWriter().setPixels(0, 0, width, height,
                PixelFormat.getIntArgbInstance(), pixels, 0, width);
        return image;
    }

    /** 自绘 16x16 程序图标：蓝底圆角 + 白色「译」字 */
    private static BufferedImage createAppIcon() {
        int size = 16;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0x3B82F6));
        g.fillRoundRect(0, 0, size - 1, size - 1, 5, 5);
        g.setColor(Color.WHITE);
        g.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        g.drawString("译", 2, 12);
        g.dispose();
        return img;
    }

    @Override
    public void stop() {
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
