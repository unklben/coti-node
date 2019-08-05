package utils;

import io.coti.basenode.data.AddressData;
import io.coti.basenode.data.Hash;
import io.coti.fullnode.http.AddressBulkRequest;
import io.coti.fullnode.http.AddressesExistsResponse;

import java.util.*;

public class AddressTestUtils {

    public static AddressData generateRandomAddressData() {
        return new AddressData(HashTestUtils.generateRandomAddressHash());
    }

    public static List<AddressData> generateListOfRandomAddressData(int listSize) {

        List<AddressData> addresses = new ArrayList<>();
        for (int i = 0; i < listSize; i++) {
            addresses.add(generateRandomAddressData());
        }
        return addresses;
    }

    public static Set<AddressData> generateSetOfRandomAddressData(int listSize) {
        Set<AddressData> addresses = new HashSet<>();
        for (int i = 0; i < listSize; i++) {
            addresses.add(generateRandomAddressData());
        }
        return addresses;
    }

    public static boolean equals(AddressesExistsResponse expected, Object actual) {
        if (expected == actual) return true;
        if (!(actual instanceof AddressesExistsResponse)) return false;
        AddressesExistsResponse actualCasted = (AddressesExistsResponse) actual;
        Iterator<Map.Entry<String, Boolean>> thisItr = expected.getAddresses().entrySet().iterator();
        Iterator<Map.Entry<String, Boolean>> otherItr = actualCasted.getAddresses().entrySet().iterator();
        while (thisItr.hasNext() && otherItr.hasNext()) {
            Map.Entry<String, Boolean> thisEntry = thisItr.next();
            Map.Entry<String, Boolean> otherEntry = otherItr.next();
            if (!thisEntry.equals(otherEntry))
                return false;
        }
        return !(thisItr.hasNext() || otherItr.hasNext());
    }

    public static LinkedHashMap<String, Boolean> initMapWithHashes(Hash... addressHashes){
        LinkedHashMap<String, Boolean> responseMap = new LinkedHashMap<>();
        Arrays.stream(addressHashes).forEach(addreassHash -> responseMap.put(addreassHash.toHexString(),null));
        return responseMap;
    }

    public static AddressesExistsResponse generateExpectedResponse(LinkedHashMap<String, Boolean> responseMapHashInitiated, Boolean... expectedAddressUsageStatuses) {
        int i = 0;
        for(Map.Entry<String,Boolean> entry: responseMapHashInitiated.entrySet()){
            entry.setValue(expectedAddressUsageStatuses[i]);
            i++;
        }
        return new AddressesExistsResponse(responseMapHashInitiated);
    }

    public static AddressBulkRequest generateAddressBulkRequest(Hash... addressHashes){
        List<Hash> addressHashesList = new ArrayList<>();
        Arrays.stream(addressHashes).forEach(addressHash -> addressHashesList.add(addressHash));

        AddressBulkRequest addressBulkRequest = new AddressBulkRequest();
        addressBulkRequest.setAddresses(addressHashesList);
        return addressBulkRequest;
    }
}
