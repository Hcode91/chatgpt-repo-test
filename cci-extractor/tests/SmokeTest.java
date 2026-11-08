import com.retrotools.cciextractor.CciArchive;
import java.io.File;
import java.io.FileInputStream;
import java.nio.channels.FileChannel;
import java.util.*;

public final class SmokeTest {
  public static void main(String[] args) throws Exception {
    if (args.length != 1) throw new IllegalArgumentException("Usage: SmokeTest /path/to/decrypted.cci");
    try (FileInputStream f = new FileInputStream(new File(args[0])); FileChannel c = f.getChannel()) {
      CciArchive.Info info = CciArchive.parse(c);
      System.out.println("Partitions: " + info.partitions);
      System.out.println("Files: " + info.entries.size());
      System.out.println("Notes: " + info.notes);
      for (CciArchive.Entry e : info.entries) if (e.path.endsWith("/TitleLogo.btex")) {
        System.out.println("Found title logo: " + e.path + " offset=" + e.offset + " size=" + e.size);
        byte[] magic = new byte[4];
        c.read(java.nio.ByteBuffer.wrap(magic),e.offset);
        System.out.println("Starts: " + Arrays.toString(magic));
      }
      if (info.entries.size() < 800) throw new AssertionError("Expected many Yumi files");
    }
  }
}
