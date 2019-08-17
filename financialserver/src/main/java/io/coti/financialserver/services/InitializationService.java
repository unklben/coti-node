package io.coti.financialserver.services;

import io.coti.basenode.crypto.NodeCryptoHelper;
import io.coti.basenode.data.DspConsensusResult;
import io.coti.basenode.data.NetworkNodeData;
import io.coti.basenode.data.NodeType;
import io.coti.basenode.data.TransactionData;
import io.coti.basenode.data.interfaces.IPropagatable;
import io.coti.basenode.services.BaseNodeInitializationService;
import io.coti.basenode.services.interfaces.ICommunicationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;

import static io.coti.basenode.constants.BaseNodeApplicationConstant.NETWORK_TYPE;
import static io.coti.basenode.constants.BaseNodeApplicationConstant.NODE_IP;

@Slf4j
@Service
public class InitializationService extends BaseNodeInitializationService {

    @Value("${propagation.port}")
    private String propagationPort;
    @Value("${server.port}")
    private String serverPort;
    @Value("${server.url}")
    private String webServerUrl;
    @Autowired
    private ICommunicationService communicationService;
    private EnumMap<NodeType, List<Class<? extends IPropagatable>>> publisherNodeTypeToMessageTypesMap = new EnumMap<>(NodeType.class);
    @Autowired
    private DistributionService distributionService;
    @Autowired
    private FundDistributionService fundDistributionService;

    @PostConstruct
    public void init() {
        try {
            super.initDB();
            super.createNetworkNodeData();
            super.getNetwork();

            publisherNodeTypeToMessageTypesMap.put(NodeType.ZeroSpendServer, Arrays.asList(TransactionData.class, DspConsensusResult.class));
            communicationService.initSubscriber(NodeType.FinancialServer, publisherNodeTypeToMessageTypesMap);

            communicationService.initPublisher(propagationPort, NodeType.FinancialServer);

            NetworkNodeData zerospendNetworkNodeData = networkService.getSingleNodeData(NodeType.ZeroSpendServer);
            if (zerospendNetworkNodeData == null) {
                log.error("No zerospend server exists in the network got from the node manager. Exiting from the application");
                System.exit(SpringApplication.exit(applicationContext));
            }
            networkService.setRecoveryServerAddress(zerospendNetworkNodeData.getHttpFullAddress());
            communicationService.addSubscription(zerospendNetworkNodeData.getPropagationFullAddress(), NodeType.ZeroSpendServer);
            networkService.addListToSubscription(networkService.getMapFromFactory(NodeType.DspNode).values());

            super.init();

            distributionService.distributeToInitialFunds();
            fundDistributionService.initReservedBalance();
        } catch (Exception e) {
            log.error("Errors at {}", this.getClass().getSimpleName());
            log.error("{}: {}", e.getClass().getName(), e.getMessage());
            System.exit(SpringApplication.exit(applicationContext));
        }
    }

    @Override
    protected NetworkNodeData createNodeProperties() {
        NetworkNodeData networkNodeData = new NetworkNodeData(NodeType.FinancialServer, NODE_IP, serverPort, NodeCryptoHelper.getNodeHash(), NETWORK_TYPE);
        networkNodeData.setPropagationPort(propagationPort);
        networkNodeData.setWebServerUrl(webServerUrl);
        return networkNodeData;

    }
}

