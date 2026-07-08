package org.opencrawling.core.result;

public sealed interface ScanResult permits ScanResult.Success, ScanResult.Failure, ScanResult.Ignored {
    record Success(String documentId, String version) implements ScanResult {}
    record Failure(String documentId, Throwable error) implements ScanResult {}
    record Ignored(String documentId, String reason) implements ScanResult {}
    
    default String summarize() {
        return switch (this) {
            case Success s -> "Successfully scanned: " + s.documentId() + " (v: " + s.version() + ")";
            case Failure f -> "Failed to scan: " + f.documentId() + " due to " + f.error().getMessage();
            case Ignored i -> "Ignored: " + i.documentId() + " Reason: " + i.reason();
        };
    }
}
