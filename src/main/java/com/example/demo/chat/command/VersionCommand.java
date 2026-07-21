package com.example.demo.chat.command;

import com.example.demo.chat.CommandManager;
import com.example.demo.chat.command.ICommand;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class VersionCommand implements ICommand {

    @Autowired
    @Lazy
    private CommandManager commandManager;

    @Override
    public String getName() {
        return "version";
    }

    @Override
    public String getDescription() {
        return "显示当前版本";
    }

    @Override
    public String execute(String[] args) {
        return commandManager.getVersion();
    }
}
