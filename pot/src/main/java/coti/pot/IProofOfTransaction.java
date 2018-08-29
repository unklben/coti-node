package coti.pot;

import java.util.List;

public interface IProofOfTransaction {
    List<IAlgorithm.AlgorithmTypes> getHashingAlgorithms();
    int[] hash(byte[] data, byte[] target);
    boolean verify(byte[] data, int[] nonce, byte[] target);
}