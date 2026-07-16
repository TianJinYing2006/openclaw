package com.example.ykdsummer.dto;

public class CommandRequest {
    private  String command;
   private String city;
    public String getCommand() {
        return command;
    }
    public void setCommand(String command) {
        this.command = command;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }
}
