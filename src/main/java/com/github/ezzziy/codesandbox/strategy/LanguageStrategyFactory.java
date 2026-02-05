package com.github.ezzziy.codesandbox.strategy;

import com.github.ezzziy.codesandbox.common.enums.LanguageEnum;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 语言策略工厂
 * <p>
 * 根据语言枚举获取对应的语言策略实现
 * </p>
 *
 * @author ezzziy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LanguageStrategyFactory {

    private final List<LanguageStrategy> strategies;
    private final Map<LanguageEnum, LanguageStrategy> strategyMap = new EnumMap<>(LanguageEnum.class);

    @PostConstruct
    public void init() {
        for (LanguageStrategy strategy : strategies) {
            strategyMap.put(strategy.getLanguage(), strategy);
            log.info("注册语言策略: {} -> {}", 
                    strategy.getLanguage().getCode(), 
                    strategy.getClass().getSimpleName());
        }
        log.info("共注册 {} 种语言策略", strategyMap.size());
    }

    /**
     * 根据语言枚举获取策略
     *
     * @param language 语言枚举
     * @return 语言策略
     * @throws IllegalArgumentException 如果不支持该语言
     */
    public LanguageStrategy getStrategy(LanguageEnum language) {
        LanguageStrategy strategy = strategyMap.get(language);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的编程语言: " + language);
        }
        return strategy;
    }

    /**
     * 根据语言代码获取策略
     *
     * @param languageCode 语言代码（如 "c", "cpp", "java8"）
     * @return 语言策略
     * @throws IllegalArgumentException 如果不支持该语言
     */
    public LanguageStrategy getStrategy(String languageCode) {
        LanguageEnum language = LanguageEnum.fromCode(languageCode);
        if (language == null) {
            throw new IllegalArgumentException("不支持的编程语言: " + languageCode);
        }
        return getStrategy(language);
    }

    /**
     * 判断是否支持指定语言
     *
     * @param languageCode 语言代码
     * @return 是否支持
     */
    public boolean isSupported(String languageCode) {
        LanguageEnum language = LanguageEnum.fromCode(languageCode);
        return language != null && strategyMap.containsKey(language);
    }

    /**
     * 获取所有支持的语言
     *
     * @return 支持的语言列表
     */
    public List<LanguageEnum> getSupportedLanguages() {
        return List.copyOf(strategyMap.keySet());
    }
}
