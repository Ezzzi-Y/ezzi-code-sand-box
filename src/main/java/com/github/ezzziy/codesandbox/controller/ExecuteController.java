package com.github.ezzziy.codesandbox.controller;

import com.github.ezzziy.codesandbox.common.result.Result;
import com.github.ezzziy.codesandbox.model.dto.ExecuteRequest;
import com.github.ezzziy.codesandbox.model.vo.ExecuteResponse;
import com.github.ezzziy.codesandbox.service.ExecutionService;
import com.github.ezzziy.codesandbox.service.InputDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    private final InputDataService inputDataService;

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

    /**
     * 更新输入数据
     * <p>
     * 接收预签名URL,解析objectKey:
     * - 如果本地已存在该数据,则删除(强制下次重新下载)
     * - 如果本地不存在,则立即下载
     * </p>
     *
     * @param request 包含预签名URL的请求
     * @return 操作结果
     */
    @PostMapping("/input-data/update")
    public Result<Map<String, Object>> updateInputData(@RequestBody Map<String, String> request) {
        String presignedUrl = request.get("presignedUrl");
        
        if (presignedUrl == null || presignedUrl.trim().isEmpty()) {
            return Result.error("预签名URL不能为空");
        }

        log.info("收到输入数据更新请求: url={}", maskUrl(presignedUrl));

        try {
            // 从URL提取objectKey
            String objectKey = inputDataService.getObjectKey(presignedUrl);
            log.info("解析到objectKey: {}", objectKey);

            // 检查本地是否存在
            boolean cached = inputDataService.isCached(objectKey);
            
            Map<String, Object> response = new HashMap<>();
            response.put("objectKey", objectKey);
            response.put("wasExisting", cached);

            if (cached) {
                // 本地存在,删除缓存
                inputDataService.evictByObjectKey(objectKey);
                response.put("action", "deleted");
                response.put("message", "已删除本地缓存数据,下次访问时将重新下载");
                log.info("已删除本地数据: objectKey={}", objectKey);
            } else {
                // 本地不存在,立即下载
                var inputDataSet = inputDataService.getInputDataSet(presignedUrl);
                response.put("action", "downloaded");
                response.put("inputCount", inputDataSet.size());
                response.put("message", "输入数据已下载到本地");
                log.info("输入数据已下载: objectKey={}, count={}", objectKey, inputDataSet.size());
            }

            return Result.success(response);

        } catch (Exception e) {
            log.error("更新输入数据失败: url={}", maskUrl(presignedUrl), e);
            return Result.error("更新输入数据失败: " + e.getMessage());
        }
    }

    /**
     * 隐藏URL中的敏感信息(用于日志)
     */
    private String maskUrl(String url) {
        if (url == null || url.length() < 50) {
            return url;
        }
        return url.substring(0, 50) + "...";
    }
}
