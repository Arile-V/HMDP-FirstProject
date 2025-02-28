package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 *  HttpSession session
 */
public interface IUserService extends IService<User> {
    Result SendCode(String phone);
    Result login(LoginFormDTO loginForm);

    Result signCount();

    Result sign();

    Result logout(HttpServletRequest request);
}
