/*
 * Copyright © 2026 the original author or authors (piergiorgio@apache.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencrawling.cli.util;

/**
 * Utility class providing ANSI colors and terminal styling.
 */
public class AnsiColors {

    public static final String RESET = "\u001B[0m";
    public static final String BOLD = "\u001B[1m";
    public static final String RED = "\u001B[31m";
    public static final String GREEN = "\u001B[32m";
    public static final String YELLOW = "\u001B[33m";
    public static final String BLUE = "\u001B[34m";
    public static final String PURPLE = "\u001B[35m";
    public static final String CYAN = "\u001B[36m";
    public static final String WHITE = "\u001B[37m";

    public static String cyan(String text) {
        return CYAN + text + RESET;
    }

    public static String green(String text) {
        return GREEN + text + RESET;
    }

    public static String yellow(String text) {
        return YELLOW + text + RESET;
    }

    public static String red(String text) {
        return RED + text + RESET;
    }

    public static String bold(String text) {
        return BOLD + text + RESET;
    }

    public static String banner() {
        return """
               %s%s
                  ___                    ____                    ___ _                  ____ _     ___
                 / _ \\ _ __  ___ _ __   / ___|_ __ __ ___      _| |_ _ __   __ _       / ___| |   |_ _|
                | | | | '_ \\/ _ \\ '_ \\ | |   | '__/ _` \\ \\ /\\ / / | | '_ \\ / _` |     | |   | |    | |
                | |_| | |_) |  __/ | | || |___| | | (_| |\\ V  V /| | | | | | (_| |  _  | |___| |___| |
                 \\___/| .__/ \\___|_| |_| \\____|_|  \\__,_| \\_/\\_/ |_|_|_| |_|\\__, | (_)  \\____|_____|___|
                      |_|                                                   |___/
               %s
               %sOpenCrawling CLI - DevSecOps & Ingestion Management (Java 25)%s
               """.formatted(BOLD, CYAN, RESET, YELLOW, RESET);
    }
}
