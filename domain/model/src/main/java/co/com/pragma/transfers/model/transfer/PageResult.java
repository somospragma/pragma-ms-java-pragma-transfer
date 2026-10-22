package co.com.pragma.transfers.model.transfer;

import java.util.List;

/** Página de resultados (TransferPage del contrato). */
public record PageResult<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public PageResult {
        content = List.copyOf(content);
    }

    /** Pagina una lista ya ordenada. */
    public static <T> PageResult<T> of(List<T> all, int page, int size) {
        int total = all.size();
        int from = (int) Math.min((long) page * size, total);
        int to = Math.min(from + size, total);
        int totalPages = (int) Math.ceil((double) total / size);
        return new PageResult<>(all.subList(from, to), page, size, total, totalPages);
    }
}
