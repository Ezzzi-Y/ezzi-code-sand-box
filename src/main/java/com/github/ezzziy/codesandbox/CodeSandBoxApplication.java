package com.github.ezzziy.codesandbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * OJ 代码执行沙箱服务
 * <p>
 * 提供安全隔离的代码执行环境，支持多种编程语言
 * </p>
 *
 * @author ezzziy
 */
@EnableScheduling
@SpringBootApplication
public class CodeSandBoxApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeSandBoxApplication.class, args);
        System.out.println("""
                
                ╔═══════════════════════════════════════════════════════╗
                ║                                                       ║
                ║      Code Sandbox Service Started Successfully        ║
                ║                                                       ║
                ║   Supported Languages: C, C++, Java8, Java11,         ║
                ║                        Python3                        ║
                ║                                                       ║
                ║   API Docs: http://localhost:6060/health              ║
                ║                                                       ║
                ╚═══════════════════════════════════════════════════════╝
                """);
    }

}
