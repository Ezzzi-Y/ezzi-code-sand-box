package com.github.ezzziy.codesandbox.service;

import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.exception.CompileException;
import com.github.ezzziy.codesandbox.exception.DangerousCodeException;
import com.github.ezzziy.codesandbox.executor.DockerCodeExecutor;
import com.github.ezzziy.codesandbox.model.dto.*;
import com.github.ezzziy.codesandbox.strategy.LanguageStrategy;
import com.github.ezzziy.codesandbox.strategy.LanguageStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 代码执行服务
 * <p>
 * 核心业务服务，负责代码执行，不涉及判题逻辑
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExecutionService {

    private final LanguageStrategyFactory strategyFactory;
    private final DockerCodeExecutor dockerCodeExecutor;
    private final ExecutionConfig executionConfig;
    private final InputDataService inputDataService;

    /**
     * 执行代码
     *
     * @param request 执行请求
     * @return 执行响应
     */
    public ExecuteResponse execute(ExecuteRequest request) {
        // 生成请求 ID（如果未提供）
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        log.info("开始执行代码: requestId={}, language={}", requestId, request.getLanguage());

        long startTime = System.currentTimeMillis();
        ExecutionTimeStats.ExecutionTimeStatsBuilder statsBuilder = ExecutionTimeStats.builder();

        try {
            // 1. 验证并获取语言策略
            if (!strategyFactory.isSupported(request.getLanguage())) {
                return ExecuteResponse.systemError(requestId,
                        "不支持的编程语言: " + request.getLanguage());
            }
            LanguageStrategy strategy = strategyFactory.getStrategy(request.getLanguage());

            // 2. 获取输入数据集
            InputDataSet inputDataSet = getInputDataSet(request);
            List<String> inputList = inputDataSet.getInputs();

            if (inputList.isEmpty()) {
                // 没有输入时，提供一个空输入
                inputDataSet = inputDataService.wrapSingleInput("");
                inputList = inputDataSet.getInputs();
            }

            // 验证输入数量
            if (inputList.size() > executionConfig.getMaxTestCases()) {
                return ExecuteResponse.systemError(requestId,
                        "输入数量超限: " + inputList.size() + " > " + executionConfig.getMaxTestCases());
            }

            log.debug("输入数据准备完成: requestId={}, inputCount={}", requestId, inputList.size());

            // 3. 确定时间和内存限制
            int timeLimit = request.getTimeLimit() != null
                    ? request.getTimeLimit()
                    : executionConfig.getRunTimeout() * 1000;
            int memoryLimit = request.getMemoryLimit() != null
                    ? request.getMemoryLimit()
                    : executionConfig.getMemoryLimit();

            // 4. 执行代码
            long execStart = System.currentTimeMillis();
            DockerCodeExecutor.ExecuteResult executeResult = dockerCodeExecutor.execute(
                    strategy,
                    request.getCode(),
                    inputList,
                    requestId,
                    timeLimit,
                    memoryLimit
            );
            statsBuilder.runTime(System.currentTimeMillis() - execStart);

            // 5. 构建响应
            statsBuilder.totalTime(System.currentTimeMillis() - startTime);
            ExecuteResponse response = ExecuteResponse.success(
                    requestId,
                    executeResult.compileOutput(),
                    executeResult.results()
            );
            response.setTimeStats(statsBuilder.build());

            log.info("代码执行完成: requestId={}, resultCount={}, totalTime={}ms",
                    requestId, executeResult.results().size(), System.currentTimeMillis() - startTime);

            return response;

        } catch (CompileException e) {
            log.warn("编译错误: requestId={}, error={}", requestId, e.getMessage());
            return ExecuteResponse.compileError(requestId, e.getMessage());

        } catch (DangerousCodeException e) {
            log.warn("危险代码: requestId={}, pattern={}", requestId, e.getPattern());
            return ExecuteResponse.dangerousCode(requestId, e.getMessage());

        } catch (IllegalArgumentException e) {
            log.warn("参数错误: requestId={}, error={}", requestId, e.getMessage());
            return ExecuteResponse.systemError(requestId, e.getMessage());

        } catch (Exception e) {
            log.error("执行异常: requestId={}", requestId, e);
            return ExecuteResponse.systemError(requestId, "系统错误: " + e.getMessage());
        }
    }

    /**
     * 获取输入数据集
     * <p>
     * 根据请求类型获取输入数据：
     * 1. 直接输入模式：将单个输入包装为数据集（不缓存）
     * 2. URL 模式：从预签名 URL 下载 ZIP 并解压（按 ObjectKey 缓存）
     * </p>
     */
    private InputDataSet getInputDataSet(ExecuteRequest request) {
        if (request.isDirectInput()) {
            // 直接输入模式（不缓存）
            return inputDataService.wrapSingleInput(request.getInput());
        } else if (request.isUrlInput()) {
            // URL 模式（自动从 URL 提取 ObjectKey 并缓存）
            return inputDataService.getInputDataSet(request.getInputDataUrl());
        } else {
            // 没有输入
            return inputDataService.wrapSingleInput("");
        }
    }

    /**
     * 获取支持的语言列表
     *
     * @return 语言信息列表
     */
    public List<LanguageInfo> getSupportedLanguages() {
        return strategyFactory.getSupportedLanguages().stream()
                .map(lang -> new LanguageInfo(
                        lang.getCode(),
                        lang.getDisplayName(),
                        lang.getDockerImage(),
                        lang.getExtension()
                ))
                .toList();
    }

    /**
     * 语言信息
     */
    public record LanguageInfo(String code, String name, String dockerImage, String extension) {}
}
