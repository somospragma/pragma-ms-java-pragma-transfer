package co.com.pragma.transfers.model.account;

import co.com.pragma.transfers.model.transfer.Money;

/** Cuenta del core bancario (dueño de cuentas y saldos). */
public record Account(String accountId, String customerId, AccountStatus status, String currency,
                      Money availableBalance) {

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean isOwnedBy(String customer) {
        return customerId != null && customerId.equals(customer);
    }

    /** RN-06. */
    public boolean canCover(Money amount) {
        return availableBalance != null && availableBalance.value().compareTo(amount.value()) >= 0;
    }
}
