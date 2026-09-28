package com.malyah.accountmanager.recurrences.domain;

/** Fields a change "from this period on" can edit. The value type is not editable. */
public enum RecurrenceChangeField {
    DESCRIPTION("description"), AMOUNT("amount"), FREQUENCY("frequency"), DUE_DAY("dueDay"),
    CATEGORY("categoryId"), RESPONSIBLE("responsibleUserId");

    private final String apiName;
    RecurrenceChangeField(String apiName) { this.apiName = apiName; }
    public String apiName() { return apiName; }
    public boolean calendar() { return this == FREQUENCY || this == DUE_DAY; }
}
