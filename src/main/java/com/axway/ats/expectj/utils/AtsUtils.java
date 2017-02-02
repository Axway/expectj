package com.axway.ats.expectj.utils;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AtsUtils {
    private static final Pattern COMMAND_ARGUMENTS_PATTERN = Pattern.compile("(?:\\\"[^\\\"]*\\\")|(?:\\'[^\\']*\\')|(?:[^\\s]+)");

    public static String[] parseCommandLineArguments(String commandWithArguments) {
        ArrayList<String> commandArguments = new ArrayList<String>();
        Matcher matcher = COMMAND_ARGUMENTS_PATTERN.matcher(commandWithArguments);
        while (matcher.find()) {
            String arg = matcher.group();
            if (arg.indexOf(34) == 0 && arg.lastIndexOf(34) == arg.length() - 1 || arg.indexOf(39) == 0 && arg.lastIndexOf(39) == arg.length() - 1) {
                arg = arg.substring(1, arg.length() - 1);
            }
            commandArguments.add(arg);
        }
        return commandArguments.toArray(new String[0]);
    }
}