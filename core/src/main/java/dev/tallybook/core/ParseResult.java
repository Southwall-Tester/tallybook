package dev.tallybook.core;

/** A rejected payload never contains a transaction or raw account data. */
public final class ParseResult {
    public final Transaction transaction;
    public final String code;
    public final String message;

    private ParseResult(Transaction transaction, String code, String message) {
        this.transaction = transaction;
        this.code = code;
        this.message = message;
    }

    public boolean isSuccess() {
        return transaction != null;
    }

    static ParseResult success(Transaction transaction) {
        return new ParseResult(transaction, "OK", transaction.reviewRequired ? "已解析，请核对后使用" : "已解析");
    }

    static ParseResult failure(String code, String message) {
        return new ParseResult(null, code, message);
    }
}
