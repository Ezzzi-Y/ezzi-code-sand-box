package com.github.ezzziy.codesandbox.service.impl;

import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.dto.BatchExecuteRequest;
import com.github.ezzziy.codesandbox.exception.CompileException;
import com.github.ezzziy.codesandbox.exception.DangerousCodeException;
import com.github.ezzziy.codesandbox.executor.DockerCodeExecutor;
import com.github.ezzziy.codesandbox.model.dto.InputDataSet;
import com.github.ezzziy.codesandbox.model.dto.SingleExecuteRequest;
import com.github.ezzziy.codesandbox.model.vo.BatchExecuteResponse;
import com.github.ezzziy.codesandbox.model.vo.SingleExecuteResponse;
import com.github.ezzziy.codesandbox.service.ExecutionService;
import com.github.ezzziy.codesandbox.service.InputDataService;
import com.github.ezzziy.codesandbox.strategy.LanguageStrategy;
import com.github.ezzziy.codesandbox.strategy.LanguageStrategyFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
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
public class ExecutionServiceImpl implements ExecutionService {

    private final LanguageStrategyFactory strategyFactory;
    private final DockerCodeExecutor dockerCodeExecutor;
    private final ExecutionConfig executionConfig;
    private final InputDataService inputDataService;

    public SingleExecuteResponse executeSingle(SingleExecuteRequest request) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        log.info("开始单次执行代码: requestId={}, language={}", requestId, request.getLanguage());

        long startTime = System.currentTimeMillis();

        try {
            if (!strategyFactory.isSupported(request.getLanguage())) {
                return SingleExecuteResponse.builder()
                        .status(ExecutionStatus.SYSTEM_ERROR)
                        .errorMessage("不支持的编程语言: " + request.getLanguage())
                        .build();
            }

            LanguageStrategy strategy = strategyFactory.getStrategy(request.getLanguage());

            String input = request.getInput() == null ? "" : request.getInput();
            List<String> inputList = Collections.singletonList(input);

            int timeLimit = request.getTimeLimit() != null
                    ? request.getTimeLimit()
                    : executionConfig.getRunTimeout() * 1000;
            int memoryLimit = request.getMemoryLimit() != null
                    ? request.getMemoryLimit()
                    : executionConfig.getMemoryLimit();

            DockerCodeExecutor.ExecuteResult executeResult = dockerCodeExecutor.execute(
                    strategy,
                    request.getCode(),
                    inputList,
                    requestId,
                    timeLimit,
                        memoryLimit
            );

            long totalTime = System.currentTimeMillis() - startTime;
                    var results = executeResult.results();
                    var singleResult = results.isEmpty() ? null : results.getFirst();

                    SingleExecuteResponse response = SingleExecuteResponse.builder()
                        .status(singleResult != null ? singleResult.getStatus() : ExecutionStatus.SYSTEM_ERROR)
                    .compileOutput(executeResult.compileOutput())
                        .result(singleResult)
                    .totalTime(totalTime)
                    .build();

                    log.info("单次执行完成: requestId={}, status={}, totalTime={}ms",
                        requestId, response.getStatus(), totalTime);

            return response;

        } catch (CompileException e) {
            log.warn("编译错误: requestId={}, error={}", requestId, e.getMessage());
                    return SingleExecuteResponse.builder()
                    .status(ExecutionStatus.COMPILE_ERROR)
                    .compileOutput(e.getMessage())
                    .build();

        } catch (DangerousCodeException e) {
            log.warn("危险代码: requestId={}, pattern={}", requestId, e.getPattern());
                    return SingleExecuteResponse.builder()
                    .status(ExecutionStatus.DANGEROUS_CODE)
                    .errorMessage(e.getMessage())
                    .build();

        } catch (IllegalArgumentException e) {
            log.warn("参数错误: requestId={}, error={}", requestId, e.getMessage());
                    return SingleExecuteResponse.builder()
                    .status(ExecutionStatus.SYSTEM_ERROR)
                    .errorMessage(e.getMessage())
                    .build();

        } catch (Exception e) {
            log.error("执行异常: requestId={}", requestId, e);
                    return SingleExecuteResponse.builder()
                    .status(ExecutionStatus.SYSTEM_ERROR)
                    .errorMessage("系统错误: " + e.getMessage())
                    .build();
        }
    }

                public BatchExecuteResponse executeBatch(BatchExecuteRequest request) {
                String requestId = request.getRequestId();
                if (requestId == null || requestId.isBlank()) {
                    requestId = UUID.randomUUID().toString().substring(0, 8);
                }

                log.info("开始批量执行代码: requestId={}, language={}", requestId, request.getLanguage());
                long startTime = System.currentTimeMillis();

                try {
                    if (!strategyFactory.isSupported(request.getLanguage())) {
                    return BatchExecuteResponse.builder()
                        .status(ExecutionStatus.SYSTEM_ERROR)
                        .errorMessage("不支持的编程语言: " + request.getLanguage())
                        .build();
                    }

                    LanguageStrategy strategy = strategyFactory.getStrategy(request.getLanguage());
                    List<String> inputList = resolveBatchInputs(request);

                    if (inputList.isEmpty()) {
                    inputList = Collections.singletonList("");
                    }

                    if (inputList.size() > executionConfig.getMaxTestCases()) {
                    return BatchExecuteResponse.builder()
                        .status(ExecutionStatus.SYSTEM_ERROR)
                        .errorMessage("输入数量超限: " + inputList.size() + " > " + executionConfig.getMaxTestCases())
                        .build();
                    }

                    int timeLimit = request.getTimeLimit() != null
                        ? request.getTimeLimit()
                        : executionConfig.getRunTimeout() * 1000;
                    int memoryLimit = request.getMemoryLimit() != null
                        ? request.getMemoryLimit()
                        : executionConfig.getMemoryLimit();

                    DockerCodeExecutor.ExecuteResult executeResult = dockerCodeExecutor.execute(
                        strategy,
                        request.getCode(),
                        inputList,
                        requestId,
                        timeLimit,
                        memoryLimit
                    );

                    List<com.github.ezzziy.codesandbox.model.dto.ExecutionResult> results = executeResult.results();
                    int success = (int) results.stream().filter(r -> r.getStatus() == ExecutionStatus.SUCCESS).count();
                    int failed = results.size() - success;
                    ExecutionStatus overallStatus = failed == 0
                        ? ExecutionStatus.SUCCESS
                        : results.stream()
                        .map(com.github.ezzziy.codesandbox.model.dto.ExecutionResult::getStatus)
                        .filter(status -> status != ExecutionStatus.SUCCESS)
                        .findFirst()
                        .orElse(ExecutionStatus.RUNTIME_ERROR);

                    return BatchExecuteResponse.builder()
                        .status(overallStatus)
                        .compileOutput(executeResult.compileOutput())
                        .results(results)
                        .summary(BatchExecuteResponse.Summary.builder()
                            .total(results.size())
                            .success(success)
                            .failed(failed)
                            .build())
                        .totalTime(System.currentTimeMillis() - startTime)
                        .build();

                } catch (CompileException e) {
                    return BatchExecuteResponse.builder()
                        .status(ExecutionStatus.COMPILE_ERROR)
                        .compileOutput(e.getMessage())
                        .build();
                } catch (DangerousCodeException e) {
                    return BatchExecuteResponse.builder()
                        .status(ExecutionStatus.DANGEROUS_CODE)
                        .errorMessage(e.getMessage())
                        .build();
                } catch (Exception e) {
                    log.error("批量执行异常: requestId={}", requestId, e);
                    return BatchExecuteResponse.builder()
                        .status(ExecutionStatus.SYSTEM_ERROR)
                        .errorMessage("系统错误: " + e.getMessage())
                        .build();
                }
                }

    private List<String> resolveBatchInputs(BatchExecuteRequest request) {
        if (request.getInputDataUrl() == null || request.getInputDataUrl().isBlank()) {
            throw new IllegalArgumentException("批量执行必须提供 inputDataUrl（zip 文件 URL）");
        }

        InputDataSet inputDataSet = inputDataService.getInputDataSet(request.getInputDataUrl());
        return inputDataSet.getInputs() == null ? new ArrayList<>() : inputDataSet.getInputs();
    }

    /**
     * 获取支持的编程语言列表
     * <p>
     * 从策略工厂获取所有已注册的语言，并转换为公开的 LanguageInfo 对象
     * </p>
     *
     * @return 支持的语言信息列表，包含代码、名称、镜像、扩展名
     */
    public List<ExecutionService.LanguageInfo> getSupportedLanguages() {
        return strategyFactory.getSupportedLanguages().stream()
                .map(lang -> new ExecutionService.LanguageInfo(
                        lang.getCode(),
                        lang.getDisplayName(),
                        lang.getDockerImage(),
                        lang.getExtension()
                ))
                .toList();
    }
}
