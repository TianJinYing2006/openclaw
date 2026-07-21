package com.example.demo.chat.command;

/**
 * 命令接口 — 基于策略模式，所有命令实现此接口
 */
public interface ICommand {

    /** 命令名称（唯一标识，小写） */
    String getName();

    /** 命令描述 */
    String getDescription();

    /**
     * 执行命令
     * @param args 命令参数（不含命令名自身）
     * @return 命令输出文本
     */
    String execute(String[] args);
}
