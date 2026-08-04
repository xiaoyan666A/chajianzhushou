package com.chajianzhushou.app;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 统一线程池：项目内所有后台任务统一从这里取线程，
 * 避免到处 new Thread 造成线程数量不受控、难以统一管理。
 * 线程为守护线程，不阻止进程退出。
 */
public final class Threads {

    private Threads() {}

    private static final int IO_THREADS = Math.max(4, Runtime.getRuntime().availableProcessors());
    private static final AtomicInteger IO_SEQ = new AtomicInteger(1);
    private static final ExecutorService IO = Executors.newFixedThreadPool(IO_THREADS, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "io-" + IO_SEQ.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    /** 后台 IO/计算任务统一执行器 */
    public static ExecutorService io() {
        return IO;
    }
}
