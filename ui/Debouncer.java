package com.example.test.translator.ui;

import javafx.animation.PauseTransition;
import javafx.util.Duration;

/**
 * 防抖工具：动作触发后，若指定时长内无新触发则执行回调。
 * 用于「输入停顿 800ms 后自动翻译」。
 */
public class Debouncer {

    private final PauseTransition pause;

    public Debouncer(Duration delay, Runnable action) {
        pause = new PauseTransition(delay);
        pause.setOnFinished(e -> action.run());
    }

    /** 重置计时：每次输入变化都调用 */
    public void restart() {
        pause.stop();
        pause.playFromStart();
    }

    /** 取消未触发的回调 */
    public void cancel() {
        pause.stop();
    }
}
