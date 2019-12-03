package io.coti.financialserver.services;

import io.coti.basenode.crypto.BaseTransactionCrypto;
import io.coti.basenode.crypto.CryptoHelper;
import io.coti.basenode.crypto.NodeCryptoHelper;
import io.coti.basenode.data.*;
import io.coti.basenode.exceptions.CotiRunTimeException;
import io.coti.basenode.exceptions.CurrencyValidationException;
import io.coti.basenode.http.Response;
import io.coti.basenode.http.interfaces.IResponse;
import io.coti.basenode.services.BaseNodeMintingService;
import io.coti.financialserver.crypto.GetMintingHistoryRequestCrypto;
import io.coti.financialserver.crypto.GetTokenMintingFeeQuoteRequestCrypto;
import io.coti.financialserver.crypto.MintingFeeQuoteCrypto;
import io.coti.financialserver.crypto.TokenMintingCrypto;
import io.coti.financialserver.data.MintedTokenData;
import io.coti.financialserver.data.MintingFeeQuoteData;
import io.coti.financialserver.data.MintingRecordData;
import io.coti.financialserver.data.ReservedAddress;
import io.coti.financialserver.http.*;
import io.coti.financialserver.http.data.MintingFeeQuoteResponseData;
import io.coti.financialserver.http.data.TokenMintingFeeResponseData;
import io.coti.financialserver.model.MintingRecords;
import io.coti.financialserver.model.UserTokenGenerations;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import static io.coti.basenode.http.BaseNodeHttpStringConstants.INVALID_SIGNATURE;
import static io.coti.basenode.http.BaseNodeHttpStringConstants.STATUS_ERROR;
import static io.coti.financialserver.http.HttpStringConstants.*;

@Slf4j
@Service
public class MintingService extends BaseNodeMintingService {

    private final static int MINTING_FEE_QUOTE_EXPIRATION_MINUTES = 60;
    @Value("${financialserver.seed}")
    private String seed;
    @Autowired
    private TokenMintingCrypto tokenMintingCrypto;
    @Autowired
    private UserTokenGenerations userTokenGenerations;
    @Autowired
    private FeeService feeService;
    @Autowired
    private MintingRecords mintingRecords;
    @Autowired
    private TransactionCreationService transactionCreationService;
    @Autowired
    private NodeCryptoHelper nodeCryptoHelper;
    @Autowired
    private GetMintingHistoryRequestCrypto getMintingHistoryRequestCrypto;
    @Autowired
    private GetTokenMintingFeeQuoteRequestCrypto getTokenMintingFeeQuoteRequestCrypto;
    @Autowired
    private MintingFeeQuoteCrypto mintingFeeQuoteCrypto;

    private BlockingQueue<TransactionData> confirmedTokenMintingFeeTransactionQueue;
    private Thread confirmedTokenMintingFeeTransactionThread;

    @Override
    public void init() {
        super.init();
        initQueuesAndThreads();
    }

    private void initQueuesAndThreads() {
        confirmedTokenMintingFeeTransactionQueue = new LinkedBlockingQueue<>();
        confirmedTokenMintingFeeTransactionThread = new Thread(this::handleConfirmedTokenMintingTransactions);
        confirmedTokenMintingFeeTransactionThread.start();
    }

    private void addToTransactionQueue(BlockingQueue<TransactionData> queue, TransactionData transactionData) {
        try {
            queue.put(transactionData);
        } catch (InterruptedException e) {
            log.error("Interrupted while waiting for insertion of transaction {} into blocking queue.", transactionData.getHash());
            Thread.currentThread().interrupt();
        }
    }

    public void addToConfirmedTokenMintingFeeTransactionQueue(TransactionData transactionData) {
        addToTransactionQueue(confirmedTokenMintingFeeTransactionQueue, transactionData);
    }

    public ResponseEntity<IResponse> getTokenMintingFee(MintingTokenFeeRequest mintingTokenFeeRequest) {
        try {
            CurrencyData currencyData = validateMintingTokenFeeRequestAndGetCurrencyData(mintingTokenFeeRequest);
            if (!validateTokenSupplyAvailableAndQuoteAmount(mintingTokenFeeRequest, currencyData)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(TOKEN_MINTING_REQUEST_INVALID_FOR_WARRANT, STATUS_ERROR));
            }
            if (!isAddressValid(mintingTokenFeeRequest.getTokenMintingData().getReceiverAddress())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(TOKEN_MINTING_REQUEST_INVALID_ADDRESS, STATUS_ERROR));
            }
            return createTokenMintingFee(mintingTokenFeeRequest, currencyData);
        } catch (CurrencyValidationException e) {
            String error = String.format("%s. Exception: %s", TOKEN_MINTING_FEE_FAILURE, e.getMessageAndCause());
            return ResponseEntity.badRequest().body(new Response(error, STATUS_ERROR));
        } catch (CotiRunTimeException e) {
            String error = String.format("%s. Exception: %s", TOKEN_MINTING_FEE_FAILURE, e.getMessageAndCause());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Response(error, STATUS_ERROR));
        } catch (Exception e) {
            String error = String.format("%s. Exception: %s", TOKEN_MINTING_FEE_FAILURE, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Response(error, STATUS_ERROR));
        }
    }

    private ResponseEntity<IResponse> createTokenMintingFee(MintingTokenFeeRequest mintingTokenFeeRequest, CurrencyData currencyData) {
        try {
            BigDecimal mintingFee = null;
            MintingFeeQuoteData mintingFeeQuoteData = mintingTokenFeeRequest.getMintingFeeQuoteData();
            TokenMintingData tokenMintingData = mintingTokenFeeRequest.getTokenMintingData();
            if (mintingFeeQuoteData != null && isStillValid(mintingFeeQuoteData)) {
                if (!mintingFeeQuoteCrypto.verifySignature(mintingFeeQuoteData)) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(INVALID_SIGNATURE, STATUS_ERROR));
                }
                if (!mintingFeeQuoteData.getMintingFee().equals(tokenMintingData.getFeeAmount())) {
                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(TOKEN_MINTING_REQUEST_INVALID_WARRANT_MISMATCHED, STATUS_ERROR));
                }
                mintingFee = mintingFeeQuoteData.getMintingFee();
            }
            if (mintingFee == null) {
                mintingFee = feeService.calculateTokenMintingFee(tokenMintingData.getMintingAmount(), Instant.now(), currencyData);
            }
            TokenMintingFeeBaseTransactionData tokenMintingFeeBaseTransactionData = new TokenMintingFeeBaseTransactionData(feeService.networkFeeAddress(),
                    currencyService.getNativeCurrencyHash(), NodeCryptoHelper.getNodeHash(), mintingFee, Instant.now(), tokenMintingData);
            setTokenMintingFeeHash(tokenMintingFeeBaseTransactionData);
            signTokenMintingFee(tokenMintingFeeBaseTransactionData);
            TokenMintingFeeResponseData tokenMintingFeeResponseData = new TokenMintingFeeResponseData(tokenMintingFeeBaseTransactionData);
            return ResponseEntity.status(HttpStatus.CREATED).body(new TokenMintingFeeResponse(tokenMintingFeeResponseData));
        } catch (Exception e) {
            log.error("{}: {}", e.getClass().getName(), e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Response(e.getMessage(), STATUS_ERROR));
        }
    }

    private CurrencyData validateMintingTokenFeeRequestAndGetCurrencyData(MintingTokenFeeRequest mintingTokenFeeRequest) {
        TokenMintingData tokenMintingData = mintingTokenFeeRequest.getTokenMintingData();
        if (!tokenMintingCrypto.verifySignature(tokenMintingData)) {
            throw new CurrencyValidationException(TOKEN_MINTING_REQUEST_INVALID_SIGNATURE);
        }
        CurrencyData currencyData = currencies.getByHash(tokenMintingData.getMintingCurrencyHash());
        if (currencyData == null) {
            throw new CurrencyValidationException(TOKEN_MINTING_REQUEST_INVALID_CURRENCY);
        }
        if (!currencyData.getOriginatorHash().equals(tokenMintingData.getSignerHash())) {
            throw new CurrencyValidationException(TOKEN_MINTING_REQUEST_INVALID_ORIGINATOR);
        }
        return currencyData;
    }

    private boolean isAddressValid(Hash address) {
        return CryptoHelper.isAddressValid(address);
    }

    private boolean validateTokenSupplyAvailableAndQuoteAmount(MintingTokenFeeRequest mintingTokenFeeRequest, CurrencyData currencyData) {
        MintingRecordData mintingRecordData;

        synchronized (addLockToLockMap(currencyData.getHash())) {
            mintingRecordData = mintingRecords.getByHash(currencyData.getHash());
            if (mintingRecordData == null) {
                mintingRecordData = new MintingRecordData(currencyData.getHash());
                mintingRecords.put(mintingRecordData);
            }
            removeLockFromLocksMap(currencyData.getHash());
        }

        if (mintingTokenFeeRequest.getMintingFeeQuoteData() != null) {
            MintingFeeQuoteData mintingFeeQuoteData = mintingTokenFeeRequest.getMintingFeeQuoteData();
            if (!mintingFeeQuoteData.getMintingAmount().equals(mintingTokenFeeRequest.getMintingFeeQuoteData().getMintingAmount())
                    || !mintingFeeQuoteData.getCurrencyHash().equals(mintingTokenFeeRequest.getMintingFeeQuoteData().getCurrencyHash())
                    || !currencyData.getOriginatorHash().equals(mintingTokenFeeRequest.getTokenMintingData().getSignerHash())) {
                return false;
            }
        }
        return true;
    }

    private HashSet<Hash> getUserTokenHashes(Hash userHash) {
        HashSet<Hash> userTokenHashes = new HashSet<>();
        UserTokenGenerationData userTokenGenerationData = userTokenGenerations.getByHash(userHash);
        if (userTokenGenerationData != null) {
            Collection<Hash> currencyHashes = userTokenGenerationData.getTransactionHashToCurrencyMap().values();
            currencyHashes.forEach(currencyHash -> fillUserTokenHashes(userTokenHashes, currencyHash));
        }
        return userTokenHashes;
    }

    private void fillUserTokenHashes(HashSet<Hash> userTokenHashes, Hash currencyHash) {
        if (currencyHash != null) {
            CurrencyData currencyData = currencies.getByHash(currencyHash);
            if (currencyData != null) {
                userTokenHashes.add(currencyData.getHash());
            }
        }
    }

    public ResponseEntity<IResponse> getTokenMintingHistory(GetMintingHistoryRequest getMintingHistoryRequest) {
        try {
            if (!getMintingHistoryRequestCrypto.verifySignature(getMintingHistoryRequest)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(INVALID_SIGNATURE, STATUS_ERROR));
            }
            HashSet<Hash> userTokenHashes = getUserTokenHashes(getMintingHistoryRequest.getUserHash());

            Map<Hash, Map<Instant, MintedTokenData>> mintingHistory = new HashMap<>();
            if (userTokenHashes.isEmpty()) {
                return ResponseEntity.ok(new GetMintingHistoryResponse(mintingHistory));
            }
            userTokenHashes.forEach(currencyHash -> fillMintingHistory(currencyHash, mintingHistory));
            return ResponseEntity.ok(new GetMintingHistoryResponse(mintingHistory));
        } catch (Exception e) {
            log.error("Error at getting user minting history: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Response(e.getMessage(), STATUS_ERROR));
        }
    }

    private void fillMintingHistory(Hash currencyHash, Map<Hash, Map<Instant, MintedTokenData>> mintingHistory) {
        synchronized (addLockToLockMap(currencyHash)) {
            MintingRecordData mintingRecordData = mintingRecords.getByHash(currencyHash);
            if (mintingRecordData != null) {
                Map<Instant, MintedTokenData> mintingTokenHistory = mintingRecordData.getMintingHistory();
                mintingHistory.put(currencyHash, mintingTokenHistory);
                mintingRecords.put(mintingRecordData);
            }
            removeLockFromLocksMap(currencyHash);
        }
    }

    private void handleConfirmedTokenMintingTransactions() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                TransactionData tokenMintingTransaction = confirmedTokenMintingFeeTransactionQueue.take();
                TokenMintingFeeBaseTransactionData tokenMintingFeeBaseTransactionData = getTokenMintingFeeData(tokenMintingTransaction);
                if (tokenMintingFeeBaseTransactionData == null) {
                    log.error("TokenMinting transaction {} without TMBT", tokenMintingTransaction.getHash());
                    continue;
                }
                TokenMintingData tokenMintingFeeBaseTransactionServiceData = tokenMintingFeeBaseTransactionData.getServiceData();
                Hash mintingCurrencyHash = tokenMintingFeeBaseTransactionServiceData.getMintingCurrencyHash();
                synchronized (addLockToLockMap(mintingCurrencyHash)) {
                    MintingRecordData mintingRecordData = mintingRecords.getByHash(mintingCurrencyHash);
                    if (mintingRecordData == null) {
                        log.error("TokenMinting transaction {} without minting record", tokenMintingTransaction.getHash());
                        continue;
                    }

                    int genesisAddressIndex = Math.toIntExact(ReservedAddress.GENESIS_ONE.getIndex());
                    Hash cotiGenesisAddress = nodeCryptoHelper.generateAddress(seed, genesisAddressIndex);
                    BigDecimal newAmountToMint = tokenMintingFeeBaseTransactionServiceData.getMintingAmount();
                    Hash initialTransactionHash = transactionCreationService.createInitialTransaction(newAmountToMint, mintingCurrencyHash,
                            cotiGenesisAddress, tokenMintingFeeBaseTransactionServiceData.getReceiverAddress(), genesisAddressIndex);

                    if (initialTransactionHash != null) {
                        log.info("Minting transaction {} for token {} successfully created. The amount is {}", initialTransactionHash,
                                mintingCurrencyHash, newAmountToMint);
                        MintedTokenData mintedTokenData = new MintedTokenData(mintingCurrencyHash,
                                Instant.now(), newAmountToMint, initialTransactionHash, tokenMintingTransaction.getHash());
                        mintingRecordData.getMintingHistory().put(mintedTokenData.getMintingTime(), mintedTokenData);
                        mintingRecords.put(mintingRecordData);
                    }
                    removeLockFromLocksMap(mintingCurrencyHash);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public ResponseEntity<IResponse> getTokenMintingFeeQuote(GetTokenMintingFeeQuoteRequest getTokenMintingFeeQuoteRequest) {
        try {
            if (!getTokenMintingFeeQuoteRequestCrypto.verifySignature(getTokenMintingFeeQuoteRequest)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(INVALID_SIGNATURE, STATUS_ERROR));
            }
            Hash currencyHash = getTokenMintingFeeQuoteRequest.getCurrencyHash();

            CurrencyData currencyData = currencies.getByHash(currencyHash);
            if (currencyData == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(TOKEN_MINTING_FEE_QUOTE_REQUEST_INVALID_CURRENCY, STATUS_ERROR));
            }
            if (!currencyData.getOriginatorHash().equals(getTokenMintingFeeQuoteRequest.getUserHash())) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new Response(TOKEN_MINTING_FEE_QUOTE_REQUEST_INVALID_ORIGINATOR, STATUS_ERROR));
            }
            Instant createTime = Instant.now();
            BigDecimal mintingAmount = getTokenMintingFeeQuoteRequest.getMintingAmount();
            BigDecimal feeQuoteAmount = feeService.calculateTokenMintingFee(mintingAmount, createTime, currencyData);
            MintingFeeQuoteData mintingFeeQuoteData = new MintingFeeQuoteData(currencyHash, createTime, mintingAmount, feeQuoteAmount);
            mintingFeeQuoteCrypto.signMessage(mintingFeeQuoteData);

            return ResponseEntity.status(HttpStatus.CREATED).body(new GetTokenMintingFeeQuoteResponse(new MintingFeeQuoteResponseData(mintingFeeQuoteData)));
        } catch (Exception e) {
            log.error("Error at user minting tokens fee quote request: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new Response(e.getMessage(), STATUS_ERROR));
        }
    }

    private void setTokenMintingFeeHash(TokenMintingFeeBaseTransactionData tokenMintingFeeBaseTransactionData) throws ClassNotFoundException {
        BaseTransactionCrypto.TokenMintingFeeBaseTransactionData.setBaseTransactionHash(tokenMintingFeeBaseTransactionData);
    }

    private void signTokenMintingFee(TokenMintingFeeBaseTransactionData tokenMintingFeeBaseTransactionData) {
        tokenMintingFeeBaseTransactionData.setSignature(nodeCryptoHelper.signMessage(tokenMintingFeeBaseTransactionData.getHash().getBytes()));
    }

    private boolean isStillValid(MintingFeeQuoteData mintingFeeQuoteData) {
        Instant createTime = mintingFeeQuoteData.getCreateTime();
        return createTime.isAfter(Instant.now().minus(MINTING_FEE_QUOTE_EXPIRATION_MINUTES, ChronoUnit.MINUTES)) && createTime.isBefore(Instant.now());
    }

    @Override
    public void validateMintingBalances() {
        mintingRecords.forEach(mintingRecordData -> {
                    Hash tokenHash = mintingRecordData.getHash();
                    BigDecimal mintingAmount = mintingRecordData.getMintingHistory().values().stream()
                            .map(MintedTokenData::getMintingAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal tokenMintedFoundAmount = getTokenMintedAmount(tokenHash);
                    if (!mintingAmount.equals(tokenMintedFoundAmount)) {
                        log.error("Minting balance validation identified mismatch for currency {}, expected {} found {}",
                                tokenHash, mintingAmount, tokenMintedFoundAmount);
                    }
                }
        );
        log.info("Minting Balance Validation completed");
    }

}