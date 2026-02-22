package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.common.result.Result;
import com.github.ezzziy.codesandbox.model.dto.BatchExecuteRequest;
import com.github.ezzziy.codesandbox.model.dto.SingleExecuteRequest;
import com.github.ezzziy.codesandbox.model.vo.BatchExecuteResponse;
import com.github.ezzziy.codesandbox.model.vo.SingleExecuteResponse;
import com.github.ezzziy.codesandbox.service.ExecutionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 代码执行控制器
 * <p>
 * 提供代码执行的 API 接口
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@RestController
@RequestMapping("/execute")
@RequiredArgsConstructor
public class ExecuteController {

    private final ExecutionService executionService;

    /**
     * 单次执行代码（仅支持直接输入）
     *
     * @param request 执行请求
     * @return 执行响应
     */
    @PostMapping("/single")
    public Result<SingleExecuteResponse> executeSingle(@Valid @RequestBody SingleExecuteRequest request) {
        log.info("收到单次执行请求: requestId={}, language={}",
                request.getRequestId(), request.getLanguage());

        SingleExecuteResponse response = executionService.executeSingle(request);
        return Result.success(response);
    }

    /**
     * 批量执行代码（多测试用例）
     *
     * @param request 批量执行请求
     * @return 执行响应
     */
    @PostMapping("/batch")
    public Result<BatchExecuteResponse> executeBatch(@Valid @RequestBody BatchExecuteRequest request) {
        log.info("收到批量执行请求: requestId={}, language={}",
                request.getRequestId(), request.getLanguage());

        BatchExecuteResponse response = executionService.executeBatch(request);
        return Result.success(response);
    }

    /**
     * 获取支持的语言列表
     *
     * @return 语言列表
     */
    @GetMapping("/languages")
    public Result<List<ExecutionService.LanguageInfo>> getSupportedLanguages() {
        List<ExecutionService.LanguageInfo> languages = executionService.getSupportedLanguages();
        return Result.success(languages);
    }
}
