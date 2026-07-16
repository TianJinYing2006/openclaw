package com.example.ykdsummer.controller;

import com.example.ykdsummer.command.CommandType;
import com.example.ykdsummer.dto.CommandRequest;
import com.example.ykdsummer.service.CommandService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CommandController {

    @Autowired
    private CommandService commandService;

    @PostMapping("/api/command")
    public String execute(@RequestBody CommandRequest commandRequest) {
        CommandType commandType = CommandType.from(commandRequest.getCommand());
        return commandService.execute(commandType, commandRequest.getCity());
    }
}
