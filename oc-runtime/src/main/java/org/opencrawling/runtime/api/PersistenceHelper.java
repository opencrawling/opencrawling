package org.opencrawling.runtime.api;

import tools.jackson.databind.ObjectMapper;
import java.io.File;
import java.util.List;
import java.util.ArrayList;

public class PersistenceHelper {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String DATA_DIR = "data";

    static {
        try {
            new File(DATA_DIR).mkdirs();
        } catch (Exception e) {
            System.err.println("Could not create data directory: " + e.getMessage());
        }
    }

    public static <T> List<T> loadList(String filename, Class<T> clazz, List<T> defaults) {
        File file = new File(DATA_DIR + "/" + filename);
        if (!file.exists()) {
            save(filename, defaults);
            return new ArrayList<>(defaults);
        }
        try {
            return mapper.readValue(file, mapper.getTypeFactory().constructCollectionType(List.class, clazz));
        } catch (Exception e) {
            System.err.println("Error loading persistence list from " + filename + ": " + e.getMessage());
            return new ArrayList<>(defaults);
        }
    }

    public static <T> T loadObject(String filename, Class<T> clazz, T defaultValue) {
        File file = new File(DATA_DIR + "/" + filename);
        if (!file.exists()) {
            save(filename, defaultValue);
            return defaultValue;
        }
        try {
            return mapper.readValue(file, clazz);
        } catch (Exception e) {
            System.err.println("Error loading persistence object from " + filename + ": " + e.getMessage());
            return defaultValue;
        }
    }

    public static synchronized void save(String filename, Object data) {
        try {
            File file = new File(DATA_DIR + "/" + filename);
            mapper.writeValue(file, data);
        } catch (Exception e) {
            System.err.println("Error saving persistence to " + filename + ": " + e.getMessage());
        }
    }
}
