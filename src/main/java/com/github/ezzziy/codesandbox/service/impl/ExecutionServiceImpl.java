package com.github.ezzziy.codesandbox.service.impl;

import com.github.ezzziy.codesandbox.config.ExecutionConfig;
import com.github.ezzziy.codesandbox.exception.CompileException;
import com.github.ezzziy.codesandbox.exception.DangerousCodeException;
import com.github.ezzziy.codesandbox.executor.DockerCodeExecutor;
import com.github.ezzziy.codesandbox.model.dto.ExecuteRequest;
import com.github.ezzziy.codesandbox.model.dto.InputDataSet;
import com.github.ezzziy.codesandbox.common.enums.ExecutionStatus;
import com.github.ezzziy.codesandbox.model.vo.ExecuteResponse;
import com.github.ezzziy.codesandbox.service.ExecutionService;
import com.github.ezzziy.codesandbox.service.InputDataService;
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
public class ExecutionServiceImpl implements ExecutionService {

    private final LanguageStrategyFactory strategyFactory;
    private final DockerCodeExecutor dockerCodeExecutor;
    private final ExecutionConfig executionConfig;
    private final InputDataService inputDataService;

    /**
     * 执行用户代码
     * <p>
     * 完整执行流程：
     * 1. 为请求分配唯一 ID（如果未提供）
     * 2. 验证编程语言是否支持
     * 3. 获取输入数据（直接输入或从 URL 下载）
     * 4. 编译代码（需要编译的语言）
     * 5. 逐一执行测试用例，收集结果
     * 
     * 异常处理：
     * - CompileException: 编译错误 → COMPILE_ERROR
     * - DangerousCodeException: 检测到危险代码 → DANGEROUS_CODE
     * - IllegalArgumentException: 参数验证失败 → SYSTEM_ERROR
     * - 其他异常: 系统错误 → SYSTEM_ERROR
     * </p>
     *
     * @param request 执行请求，包含代码、语言、输入、时间/内存限制
     * @return 执行响应，包含编译输出和所有测试用例结果
     */
    public ExecuteResponse execute(ExecuteRequest request) {
        String requestId = request.getRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString().substring(0, 8);
        }

        log.info("开始执行代码: requestId={}, language={}", requestId, request.getLanguage());

        long startTime = System.currentTimeMillis();
        long compileTime = 0L;
        long runTime = 0L;

        try {
            if (!strategyFactory.isSupported(request.getLanguage())) {
                return ExecuteResponse.builder()
                        .status(ExecutionStatus.SYSTEM_ERROR)
                        .errorMessage("不支持的编程语言: " + request.getLanguage())
                        .build();
            }
            LanguageStrategy strategy = strategyFactory.getStrategy(request.getLanguage());

            InputDataSet inputDataSet = getInputDataSet(request);
            List<String> inputList = inputDataSet.getInputs();

            if (inputList.isEmpty()) {
                inputDataSet = inputDataService.wrapSingleInput("");
                inputList = inputDataSet.getInputs();
            }

            if (inputList.size() > executionConfig.getMaxTestCases()) {
                return ExecuteResponse.builder()
                        .status(ExecutionStatus.SYSTEM_ERROR)
                        .errorMessage("输入数量超限: " + inputList.size() + " > " + executionConfig.getMaxTestCases())
                        .build();
            }

            log.debug("输入数据准备完成: requestId={}, inputCount={}", requestId, inputList.size());

            int timeLimit = request.getTimeLimit() != null
                    ? request.getTimeLimit()
                    : executionConfig.getRunTimeout() * 1000;
            int memoryLimit = request.getMemoryLimit() != null
                    ? request.getMemoryLimit()
                    : executionConfig.getMemoryLimit();

            long execStart = System.currentTimeMillis();
            DockerCodeExecutor.ExecuteResult executeResult = dockerCodeExecutor.execute(
                    strategy,
                    request.getCode(),
                    inputList,
                    requestId,
                    timeLimit,
                    memoryLimit
            );
            runTime = System.currentTimeMillis() - execStart;

            long totalTime = System.currentTimeMillis() - startTime;
            ExecuteResponse response = ExecuteResponse.builder()
                    .status(ExecutionStatus.SUCCESS)
                    .compileOutput(executeResult.compileOutput())
                    .results(executeResult.results())
                    .compileTime(compileTime)
                    .runTime(runTime)
                    .totalTime(totalTime)
                    .build();

            log.info("代码执行完成: requestId={}, resultCount={}, totalTime={}ms",
                    requestId, executeResult.results().size(), System.currentTimeMillis() - startTime);

            return response;

        } catch (CompileException e) {
            log.warn("编译错误: requestId={}, error={}", requestId, e.getMessage());
            return ExecuteResponse.builder()
                    .status(ExecutionStatus.COMPILE_ERROR)
                    .compileOutput(e.getMessage())
                    .build();

        } catch (DangerousCodeException e) {
            log.warn("危险代码: requestId={}, pattern={}", requestId, e.getPattern());
            return ExecuteResponse.builder()
                    .status(ExecutionStatus.DANGEROUS_CODE)
                    .errorMessage(e.getMessage())
                    .build();

        } catch (IllegalArgumentException e) {
            log.warn("参数错误: requestId={}, error={}", requestId, e.getMessage());
            return ExecuteResponse.builder()
                    .status(ExecutionStatus.SYSTEM_ERROR)
                    .errorMessage(e.getMessage())
                    .build();

        } catch (Exception e) {
            log.error("执行异常: requestId={}", requestId, e);
            return ExecuteResponse.builder()
                    .status(ExecutionStatus.SYSTEM_ERROR)
                    .errorMessage("系统错误: " + e.getMessage())
                    .build();
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
            return inputDataService.wrapSingleInput(request.getInput());
        } else if (request.isUrlInput()) {
            return inputDataService.getInputDataSet(request.getInputDataUrl());
        } else {
            return inputDataService.wrapSingleInput("");
        }
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
