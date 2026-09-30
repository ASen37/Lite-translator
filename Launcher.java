package com.example.test.translator;

import javafx.application.Application;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * fat jar 启动入口。
 *
 * 不能直接让 java -jar 启动 {@link TranslatorMain}：JDK 的 launcher 看到主类继承
 * javafx.application.Application 后会去 boot module layer 里找 javafx.graphics 模块，
 * 而 fat jar 里的 JavaFX 是在 classpath 上，不在 module layer，所以会报
 * “JavaFX runtime components are missing”。
 *
 * 用一个不继承 Application 的普通主类间接调用 Application.launch 即可绕过这个检查。
 */
public class Launcher {

    public static void main(String[] args) {
        // JavaFX 从 classpath（fat jar）启动时，PlatformImpl 会打一条 WARNING：
        // “Unsupported JavaFX configuration: classes were loaded from 'unnamed module'”。
        // 这条警告会被某些无控制台启动器重定向到 error.log，但对程序运行没有影响。
        // 该日志的 logger 名是 "javafx"（通过 System.getLogger 创建），
        // 在启动前把它的级别调到 SEVERE，避免生成这条误导性的日志。
        Logger.getLogger("javafx").setLevel(Level.SEVERE);

        Application.launch(TranslatorMain.class, args);
    }
}
