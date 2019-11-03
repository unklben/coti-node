package io.coti.basenode.services.interfaces;

import io.coti.basenode.data.CurrencyData;
import io.coti.basenode.data.Hash;
import io.coti.basenode.data.InitiatedTokenNoticeData;
import io.coti.basenode.http.GetUpdatedCurrencyRequest;
import reactor.core.publisher.FluxSink;

public interface ICurrencyService {

    void init();

    void updateCurrencies();

    boolean verifyCurrencyExists(Hash currencyDataHash);

    CurrencyData getNativeCurrency();

    Hash getNativeCurrencyHash();

    void putCurrencyData(CurrencyData currencyData);

    void getUpdatedCurrencyBatch(GetUpdatedCurrencyRequest getUpdatedCurrencyRequest, FluxSink<CurrencyData> fluxSink);

    CurrencyData getCurrencyFromDB(Hash currencyHash);

    void handleInitiatedTokenNotice(InitiatedTokenNoticeData initiatedTokenNoticeData);
}