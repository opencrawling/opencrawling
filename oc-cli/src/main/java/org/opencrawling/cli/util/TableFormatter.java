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

import java.util.ArrayList;
import java.util.List;

/**
 * Utility for formatting tabular data into clean ANSI-formatted ASCII tables for terminal output.
 */
public class TableFormatter {

    private final List<String> headers = new ArrayList<>();
    private final List<List<String>> rows = new ArrayList<>();

    public TableFormatter setHeaders(String... headers) {
        this.headers.clear();
        this.headers.addAll(List.of(headers));
        return this;
    }

    public TableFormatter addRow(String... row) {
        this.rows.add(List.of(row));
        return this;
    }

    public String render() {
        if (headers.isEmpty() && rows.isEmpty()) {
            return "";
        }

        int columns = headers.size();
        int[] columnWidths = new int[columns];

        for (int i = 0; i < columns; i++) {
            columnWidths[i] = headers.get(i).length();
        }

        for (List<String> row : rows) {
            for (int i = 0; i < Math.min(row.size(), columns); i++) {
                String val = row.get(i) != null ? stripAnsi(row.get(i)) : "";
                if (val.length() > columnWidths[i]) {
                    columnWidths[i] = val.length();
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        String separator = buildSeparator(columnWidths);

        sb.append(separator).append("\n");
        sb.append("|");
        for (int i = 0; i < columns; i++) {
            sb.append(" ").append(padRight(headers.get(i), columnWidths[i])).append(" |");
        }
        sb.append("\n").append(separator).append("\n");

        for (List<String> row : rows) {
            sb.append("|");
            for (int i = 0; i < columns; i++) {
                String cell = i < row.size() && row.get(i) != null ? row.get(i) : "";
                int paddingLength = columnWidths[i] - stripAnsi(cell).length();
                sb.append(" ").append(cell).append(" ".repeat(Math.max(0, paddingLength))).append(" |");
            }
            sb.append("\n");
        }

        sb.append(separator);
        return sb.toString();
    }

    private String buildSeparator(int[] columnWidths) {
        StringBuilder sb = new StringBuilder("+");
        for (int width : columnWidths) {
            sb.append("-").append("-".repeat(width)).append("-+");
        }
        return sb.toString();
    }

    private String padRight(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    private String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }
}
