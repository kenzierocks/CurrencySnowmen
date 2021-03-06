/*
 * This file is part of Currency☃, licensed under the MIT License (MIT).
 *
 * Copyright (c) kenzierocks (Kenzie Togami) <http://kenzierocks.me>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package me.kenzierocks.plugins.currencysnowmen.implementation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.economy.EconomyTransactionEvent;
import org.spongepowered.api.service.context.Context;
import org.spongepowered.api.service.context.ContextCalculator;
import org.spongepowered.api.service.economy.Currency;
import org.spongepowered.api.service.economy.account.Account;
import org.spongepowered.api.service.economy.transaction.ResultType;
import org.spongepowered.api.service.economy.transaction.TransactionResult;
import org.spongepowered.api.service.economy.transaction.TransactionType;
import org.spongepowered.api.service.economy.transaction.TransactionTypes;
import org.spongepowered.api.service.economy.transaction.TransferResult;
import org.spongepowered.api.text.Text;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import me.kenzierocks.plugins.currencysnowmen.CSPlugin;
import me.kenzierocks.plugins.currencysnowmen.ExtendedCurrency;
import me.kenzierocks.plugins.currencysnowmen.implementation.Transactionals.TRData;

public class CSAccount implements Account {

    private static final Gson JSON;
    @SuppressWarnings("serial")
    private static final Type DATA_TYPE =
            new TypeToken<Table<Currency, Set<Context>, BigDecimal>>() {
            }.getType();
    static {
        GsonBuilder builder = new GsonBuilder();
        builder.registerTypeAdapter(DATA_TYPE, new DataMapAdapter());
        JSON = builder.create();
    }

    private static TransactionResult handleAction(CSAccount $this, Cause cause,
            TRData data, Supplier<TransactionResult> provideInitialState,
            Supplier<TransactionResult> ifSuccessful) {
        TransactionResult result = provideInitialState.get();
        EconomyTransactionEvent transaction =
                Transactionals.createEvent(cause, result);
        boolean canceled = Sponge.getEventManager().post(transaction);
        if (canceled) {
            return Transactionals.fail(data);
        }
        if (result.getResult() == ResultType.SUCCESS) {
            result = ifSuccessful.get();
            if (result.getResult() == ResultType.SUCCESS) {
                $this.save();
            }
        }
        return result;
    }

    private final String id;
    private final Text displayName;
    private final Table<Currency, Set<Context>, BigDecimal> currencyTable =
            HashBasedTable.create();

    protected CSAccount(String id) {
        this(id, Text.of(id));
    }

    protected CSAccount(String id, Text displayName) {
        this.id = id;
        this.displayName = displayName;
        load();
    }

    public void load() {
        Path saveLocation = CSPlugin.getInstance().getAccountSerializationDir()
                .resolve(this.id);
        if (!Files.exists(saveLocation)) {
            return;
        }
        try (
                Reader reader = Files.newBufferedReader(saveLocation)) {
            Table<Currency, Set<Context>, BigDecimal> data =
                    JSON.fromJson(reader, DATA_TYPE);
            this.currencyTable.putAll(data);
        } catch (IOException e) {
            CSPlugin.getInstance().getLogger()
                    .error("couldn't load acc " + this.id, e);
        }
    }

    public void save() {
        Path saveLocation = CSPlugin.getInstance().getAccountSerializationDir()
                .resolve(this.id);
        if (!Files.exists(saveLocation)) {
            try {
                Files.createDirectories(saveLocation.getParent());
                Files.createFile(saveLocation);
            } catch (IOException e) {
                CSPlugin.getInstance().getLogger()
                        .error("couldn't save acc " + this.id, e);
            }
        }
        try (
                Writer writer = Files.newBufferedWriter(saveLocation)) {
            JSON.toJson(this.currencyTable, DATA_TYPE, writer);
        } catch (IOException e) {
            CSPlugin.getInstance().getLogger()
                    .error("couldn't save acc " + this.id, e);
        }
    }

    private TransactionResult handleNonTransfer(Cause cause, BigDecimal from,
            BigDecimal to, Currency currency, Set<Context> contexts) {
        BigDecimal delta = to.subtract(from);
        TransactionType type = from.compareTo(to) > 0
                ? TransactionTypes.WITHDRAW : TransactionTypes.DEPOSIT;
        TRData data = new TRData(this, currency, delta, contexts, type);
        return handleAction(this, cause, data, () -> {
            if (currency instanceof ExtendedCurrency) {
                ExtendedCurrency extCur = (ExtendedCurrency) currency;
                if (!extCur.supportsNegatives()
                        && to.compareTo(BigDecimal.ZERO) < 0) {
                    return Transactionals.failNoFunds(data);
                }
                if (extCur.getMaximumAccountBalance()
                        .filter(max -> max.compareTo(to) < 0).isPresent()) {
                    return Transactionals.failMaxSize(data);
                }
            }
            return Transactionals.success(data);
        }, () -> {
            this.currencyTable.put(currency, contexts, to);
            return Transactionals.success(data);
        });
    }

    private TransactionResult handleTransfer(Cause cause, BigDecimal from,
            BigDecimal to, Currency currency, Set<Context> contexts,
            Account target) {
        BigDecimal delta = to.subtract(from);
        TransactionType type = from.compareTo(to) > 0
                ? TransactionTypes.WITHDRAW : TransactionTypes.DEPOSIT;
        TRData data = new TRData(this, currency, delta, contexts, type);
        if (!(target instanceof CSAccount)) {
            return Transactionals.fail(data);
        }
        CSAccount that = (CSAccount) target;
        BigDecimal thisAccNewVal = to;
        BigDecimal thatAccNewVal =
                that.getBalanceOrDefault(currency, contexts).subtract(delta);
        return handleAction(this, cause, data, () -> {
            if (currency instanceof ExtendedCurrency) {
                ExtendedCurrency extCur = (ExtendedCurrency) currency;
                if (!extCur.supportsNegatives() && (thisAccNewVal
                        .compareTo(BigDecimal.ZERO) < 0
                        || thatAccNewVal.compareTo(BigDecimal.ZERO) < 0)) {
                    return Transactionals.failNoFunds(data);
                }
                Optional<BigDecimal> maxBal = extCur.getMaximumAccountBalance();
                if (maxBal.filter(max -> max.compareTo(thisAccNewVal) < 0)
                        .isPresent()
                        || maxBal
                                .filter(max -> max.compareTo(thatAccNewVal) < 0)
                                .isPresent()) {
                    return Transactionals.failMaxSize(data);
                }
            }
            return Transactionals.success(data);
        }, () -> {
            this.currencyTable.put(currency, contexts, thisAccNewVal);
            that.currencyTable.put(currency, contexts, thatAccNewVal);
            return Transactionals.success(data);
        });
    }

    @Override
    public String getIdentifier() {
        return this.id;
    }

    @Override
    public Set<Context> getActiveContexts() {
        Set<ContextCalculator<Account>> ccs =
                CSEconomyService.INSTANCE.getContextCalculators();
        Set<Context> contexts = new HashSet<>();
        for (ContextCalculator<Account> contextCalculator : ccs) {
            contextCalculator.accumulateContexts(this, contexts);
        }
        return contexts.stream()
                .filter(ctxt -> ccs.stream()
                        .anyMatch(calc -> calc.matches(ctxt, this)))
                .collect(Collectors.toSet());
    }

    @Override
    public Text getDisplayName() {
        return this.displayName;
    }

    @Override
    public BigDecimal getDefaultBalance(Currency currency) {
        if (currency instanceof ExtendedCurrency) {
            return ((ExtendedCurrency) currency).getDefaultBalance();
        }
        return BigDecimal.ZERO;
    }

    @Override
    public boolean hasBalance(Currency currency, Set<Context> contexts) {
        return this.currencyTable.contains(currency, contexts);
    }

    private BigDecimal getBalanceOrDefault(Currency currency,
            Set<Context> contexts) {
        return this.currencyTable.row(currency).getOrDefault(contexts,
                getDefaultBalance(currency));
    }

    @Override
    public BigDecimal getBalance(Currency currency, Set<Context> contexts) {
        return this.currencyTable.row(currency).getOrDefault(contexts,
                BigDecimal.ZERO);
    }

    @Override
    public Map<Currency, BigDecimal> getBalances(Set<Context> contexts) {
        return this.currencyTable.column(contexts);
    }

    @Override
    public TransactionResult setBalance(Currency currency, BigDecimal amount,
            Cause cause, Set<Context> contexts) {
        return handleNonTransfer(cause, getBalance(currency, contexts), amount,
                currency, contexts);
    }

    @Override
    public TransactionResult resetBalances(Cause cause, Set<Context> contexts) {
        boolean allOk = true;
        // TODO wait for the econ api to not suck
        TRData fakedData =
                this.currencyTable.rowMap().entrySet().stream().findFirst()
                        .map(e -> new TRData(this, e.getKey(),
                                getDefaultBalance(e.getKey()), contexts,
                                TransactionTypes.WITHDRAW))
                        .orElse(null);
        for (Currency currency : this.currencyTable.rowKeySet()) {
            if (!hasBalance(currency, contexts)) {
                // don't reset balances that don't exist
                continue;
            }
            allOk &= setBalance(currency, getDefaultBalance(currency), cause,
                    contexts).getResult() == ResultType.SUCCESS;
        }
        return fakedData == null ? null
                : (allOk ? Transactionals.success(fakedData)
                        : Transactionals.fail(fakedData));
    }

    @Override
    public TransactionResult resetBalance(Currency currency, Cause cause,
            Set<Context> contexts) {
        if (!hasBalance(currency, contexts)) {
            TRData data = new TRData(this, currency, BigDecimal.ZERO, contexts,
                    TransactionTypes.WITHDRAW);
            return Transactionals.success(data);
        }
        return setBalance(currency, getDefaultBalance(currency), cause,
                contexts);
    }

    @Override
    public TransactionResult deposit(Currency currency, BigDecimal amount,
            Cause cause, Set<Context> contexts) {
        BigDecimal start = getBalanceOrDefault(currency, contexts);
        return handleNonTransfer(cause, start, start.add(amount), currency,
                contexts);
    }

    @Override
    public TransactionResult withdraw(Currency currency, BigDecimal amount,
            Cause cause, Set<Context> contexts) {
        BigDecimal start = getBalanceOrDefault(currency, contexts);
        return handleNonTransfer(cause, start, start.subtract(amount), currency,
                contexts);
    }

    @Override
    public TransferResult transfer(Account to, Currency currency,
            BigDecimal amount, Cause cause, Set<Context> contexts) {
        BigDecimal start = getBalanceOrDefault(currency, contexts);
        TransactionResult res = handleTransfer(cause, start,
                start.subtract(amount), currency, contexts, to);
        return Transactionals.transfer(res, to);
    }

}
