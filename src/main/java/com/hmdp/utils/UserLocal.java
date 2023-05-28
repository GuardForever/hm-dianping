package com.hmdp.utils;

import com.hmdp.entity.User;

public class UserLocal {
    private static final ThreadLocal<User> u1 = new ThreadLocal<>();

    public static void saveUser(User user){
        u1.set(user);
    }

    public static User getUser(){
        return u1.get();
    }

    public static void removeUser(){
        u1.remove();
    }
}
