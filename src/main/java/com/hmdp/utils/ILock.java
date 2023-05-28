package com.hmdp.utils;

/**
 * redis实现分布式锁
 */
public interface ILock {

    // 获取锁
    boolean tryLock(long timeoutSec);

    // 释放锁
    void unLock();
}
