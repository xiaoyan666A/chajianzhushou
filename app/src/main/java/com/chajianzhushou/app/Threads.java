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
    // 图片下载/解码等重任务专用线程池：避免批量图片任务占满网络请求线程，导致查询/出库排队
    private static final int DECODE_THREADS = Math.max(2, (Runtime.getRuntime().availableProcessors() + 1) / 2);
    private static final AtomicInteger DECODE_SEQ = new AtomicInteger(1);
    private static final ExecutorService DECODE = Executors.newFixedThreadPool(DECODE_THREADS, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "decode-" + DECODE_SEQ.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });
    private static final AtomicInteger IO_SEQ = new AtomicInteger(1);
    private static final ExecutorService IO = Executors.newFixedThreadPool(IO_THREADS, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "io-" + IO_SEQ.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    /** 后台 IO/计算任务统一执行器（网络请求、登录、出库等） */
    public static ExecutorService io() {
        return IO;
    }

    /** 图片下载/解码等重任务专用执行器（独立线程池，与网络请求隔离） */
    public static ExecutorService decode() {
        return DECODE;
    }
}
