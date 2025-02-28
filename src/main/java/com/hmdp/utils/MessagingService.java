package com.hmdp.utils;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;


@Component
public class MessagingService {
    @Resource
    RabbitTemplate rabbitTemplate;


}