package com.chris64233.cc.peerreview.web;

import com.chris64233.cc.peerreview.exception.BusinessRuleException;
import com.chris64233.cc.peerreview.exception.ConflictException;
import com.chris64233.cc.peerreview.exception.ResourceNotFoundException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler({BusinessRuleException.class, ConstraintViolationException.class})
    public ProblemDetail handleBusinessRule(Exception ex) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    }

    @ExceptionHandler({ConflictException.class, DataIntegrityViolationException.class})
    public ProblemDetail handleConflict(Exception ex) {
        String message = ex instanceof DataIntegrityViolationException
                ? "操作违反数据库完整性约束"
                : ex.getMessage();
        return problem(HttpStatus.CONFLICT, message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
        ProblemDetail detail = problem(HttpStatus.BAD_REQUEST, "请求参数校验失败");
        ex.getBindingResult().getFieldErrors().forEach(error ->
                detail.setProperty(error.getField(), error.getDefaultMessage()));
        return detail;
    }

    private ProblemDetail problem(HttpStatus status, String message) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, message);
        detail.setTitle(status.getReasonPhrase());
        detail.setType(URI.create("urn:cc-peer-review:error"));
        return detail;
    }
}
