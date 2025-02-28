package com.hmdp.utils;

import lombok.Data;
import lombok.Getter;

import java.time.LocalDateTime;

@Data
public class RedisData<T> {
    @Getter
    private LocalDateTime ttl;
    private T data;
    public RedisData(LocalDateTime ttl, T data) {
        this.ttl = ttl;
        this.data = data;
    }

    public RedisData() {
        this.ttl = LocalDateTime.now();
        this.data = null;
    }
}