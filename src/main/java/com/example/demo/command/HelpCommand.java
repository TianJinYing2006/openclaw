package com.example.demo.command;

import com.example.demo.control.CommandManager;
import com.example.demo.ICommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class HelpCommand implements ICommand {

    @Autowired
    @Lazy
    private CommandManager commandManager;

    @Override
    public String getName() {
        return "help";
    }

    @Override
    public String getDescription() {
        return "显示此帮助信息";
    }

    @Override
    public String execute(String[] args) {
        return commandManager.getHelpText();
    }
}
