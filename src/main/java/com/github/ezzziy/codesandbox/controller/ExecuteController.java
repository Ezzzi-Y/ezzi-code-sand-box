package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.common.result.Result;
import com.github.ezzziy.codesandbox.model.dto.ExecuteRequest;
import com.github.ezzziy.codesandbox.model.vo.ExecuteResponse;
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
     * 执行代码
     *
     * @param request 执行请求
     * @return 执行响应
     */
    @PostMapping
    public Result<ExecuteResponse> execute(@Valid @RequestBody ExecuteRequest request) {
        log.info("收到执行请求: requestId={}, language={}",
                request.getRequestId(), request.getLanguage());

        ExecuteResponse response = executionService.execute(request);
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
