package ly.anoma.hex.typing;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import org.pytorch.executorch.DType;
import org.pytorch.executorch.Tensor;

/** The runtime has a BOOL dtype but no Java fromBlob overload for boolean tensors. */
final class FutoKeyMask extends Tensor {
    private final ByteBuffer bytes;

    FutoKeyMask(int size, int active) {
        super(new long[]{1, size});
        bytes = Tensor.allocateByteBuffer(size);
        for (int i = 0; i < size; i++) {
            bytes.put(i, (byte) (i < active ? 1 : 0));
        }
    }

    @Override public DType dtype() { return DType.BOOL; }
    @Override public Buffer getRawDataBuffer() { return bytes; }
}
