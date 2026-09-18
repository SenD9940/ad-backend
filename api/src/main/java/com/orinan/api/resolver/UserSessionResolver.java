package com.orinan.api.resolver;

import com.orinan.api.annotation.UserSession;
import com.orinan.api.domain.user.controller.model.UserResponse;
import com.orinan.api.domain.user.converter.UserConverter;
import com.orinan.api.domain.user.service.UserService;
import com.orinan.db.user.UserEntity;
import com.orinan.db.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserSessionResolver implements HandlerMethodArgumentResolver {

    private final UserService userService;
    private final UserConverter userConverter;

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        var annotation = parameter.hasParameterAnnotation(UserSession.class);
        var type = parameter.getParameterType().equals(UserResponse.class);
        return annotation && type;
    }

    @Override
    public UserResponse resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer, NativeWebRequest webRequest, WebDataBinderFactory binderFactory) throws Exception {
        var requestContext = RequestContextHolder.getRequestAttributes();
        Long userId = (Long) requestContext.getAttribute("userId", RequestAttributes.SCOPE_REQUEST);
        UserEntity userEntity = userService.findByIdAndStatusWithThrow(userId, UserStatus.REGISTERED);
        return userConverter.toResponse(userEntity);
    }
}
