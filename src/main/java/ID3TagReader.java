import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

// Beispiel für das Lesen von Tags:
// java -cp target/mp3-fat.jar ID3TagReader resources/Distant_Wonders.mp3

public class ID3TagReader {
    private static final Logger LOGGER = Logger.getLogger(ID3TagReader.class.getName());

    // Konstanten als statische innere Klasse
    private static final class TagConstants {
        static final int ID3V2_HEADER_SIZE = 10;
        static final int ID3V1_TAG_SIZE = 128;
        static final byte[] ID3V2_IDENTIFIER = "ID3".getBytes(StandardCharsets.ISO_8859_1);
        static final byte[] ID3V1_IDENTIFIER = "TAG".getBytes(StandardCharsets.ISO_8859_1);

        // Flags für ID3v2
        static final int UNSYNCHRONISATION = 0x80;
        static final int EXTENDED_HEADER = 0x40;
        static final int EXPERIMENTAL = 0x20;

        // Textencoding-Map
        static final Map<Byte, Charset> ENCODINGS = Map.of(
                (byte) 0, StandardCharsets.ISO_8859_1,
                (byte) 1, StandardCharsets.UTF_16,
                (byte) 2, StandardCharsets.UTF_16BE,
                (byte) 3, StandardCharsets.UTF_8
        );
    }

    private final Path filePath;

    public ID3TagReader(String filePath) {
        this.filePath = Path.of(filePath);
    }

    public enum TagType {
        ID3V1, ID3V2, NONE
    }

    public record TagResult(TagType type, ID3v1Tag id3v1Tag, ID3v2Tag id3v2Tag) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("ID3 Tag Information:\n");
            if (id3v2Tag != null) {
                sb.append("\n=== ID3v2 Tags ===\n").append(id3v2Tag);
            }
            if (id3v1Tag != null) {
                sb.append("\n=== ID3v1 Tags ===\n").append(id3v1Tag);
            }
            if (id3v2Tag == null && id3v1Tag == null) {
                sb.append("Keine ID3 Tags gefunden.");
            }
            return sb.toString();
        }
    }

    public TagResult readTags() throws IOException {
        validateFile();

        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            ID3v2Tag id3v2Tag = readID3v2Tag(channel);
            ID3v1Tag id3v1Tag = readID3v1Tag(channel);

            if (id3v1Tag == null && id3v2Tag == null) {
                LOGGER.info(() -> "Keine ID3 Tags gefunden in: " + filePath);
                return new TagResult(TagType.NONE, null, null);
            }

            return new TagResult(
                    id3v2Tag != null ? TagType.ID3V2 : TagType.ID3V1,
                    id3v1Tag,
                    id3v2Tag
            );
        }
    }

    private void validateFile() throws IOException {
        if (!Files.exists(filePath) || !Files.isReadable(filePath) || Files.size(filePath) == 0) {
            throw new IOException("Ungültige Datei: " + filePath);
        }
    }

    private ID3v2Tag readID3v2Tag(FileChannel channel) throws IOException {
        ByteBuffer header = readBuffer(channel, 0, TagConstants.ID3V2_HEADER_SIZE);
        if (header == null) return null;

        byte[] identifier = new byte[3];
        header.get(identifier);

        if (!Arrays.equals(identifier, TagConstants.ID3V2_IDENTIFIER)) {
            return null;
        }

        int majorVersion = Byte.toUnsignedInt(header.get());
        int minorVersion = Byte.toUnsignedInt(header.get());
        int flags = Byte.toUnsignedInt(header.get());

        byte[] sizeBytes = new byte[4];
        header.get(sizeBytes);
        int tagSize = parseSynchsafeInteger(sizeBytes);

        Map<String, String> frames = readFrames(channel, tagSize);

        return new ID3v2Tag(majorVersion, minorVersion, flags, tagSize, frames);
    }

    private Map<String, String> readFrames(FileChannel channel, int tagSize) throws IOException {
        ByteBuffer tagBuffer = readBuffer(channel, channel.position(), tagSize);
        if (tagBuffer == null) return Collections.emptyMap();

        Map<String, String> frames = new HashMap<>();
        while (tagBuffer.remaining() >= 10) {
            int frameHeaderPosition = tagBuffer.position();
            String frameId = readString(tagBuffer, 4);

            int frameSize = tagBuffer.getInt();
            tagBuffer.getShort(); // Flags überspringen

            if (frameSize <= 0 || frameId.trim().isEmpty() || frameSize > tagBuffer.remaining()) {
                tagBuffer.position(frameHeaderPosition);
                break;
            }

            byte[] frameData = new byte[frameSize];
            tagBuffer.get(frameData);

            if (frameId.startsWith("T")) {
                Frame frame = parseTextFrame(frameId, frameData);
                frames.put(frame.id(), frame.value());
            }
        }
        return frames;
    }

    private Frame parseTextFrame(String frameId, byte[] frameData) {
        byte encoding = frameData[0];
        Charset charset = TagConstants.ENCODINGS.getOrDefault(encoding, StandardCharsets.ISO_8859_1);
        String value = new String(frameData, 1, frameData.length - 1, charset).trim();
        return new Frame(frameId, value);
    }

    private int parseSynchsafeInteger(byte[] bytes) {
        return ((bytes[0] & 0x7F) << 21) |
                ((bytes[1] & 0x7F) << 14) |
                ((bytes[2] & 0x7F) << 7) |
                (bytes[3] & 0x7F);
    }

    private ID3v1Tag readID3v1Tag(FileChannel channel) throws IOException {
        if (channel.size() < TagConstants.ID3V1_TAG_SIZE) {
            return null;
        }

        long position = channel.size() - TagConstants.ID3V1_TAG_SIZE;
        ByteBuffer tagBuffer = readBuffer(channel, position, TagConstants.ID3V1_TAG_SIZE);
        if (tagBuffer == null) return null;

        byte[] identifier = new byte[3];
        tagBuffer.get(identifier);

        if (!Arrays.equals(identifier, TagConstants.ID3V1_IDENTIFIER)) {
            return null;
        }

        String title = readString(tagBuffer, 30);
        String artist = readString(tagBuffer, 30);
        String album = readString(tagBuffer, 30);
        String year = readString(tagBuffer, 4);
        String comment = readString(tagBuffer, 28);
        int zeroByte = tagBuffer.get();
        int track = tagBuffer.get() & 0xFF;
        int genre = tagBuffer.get() & 0xFF;

        if (zeroByte != 0) {
            comment += (char) zeroByte + (char) track;
            track = 0;
        }

        return new ID3v1Tag(title.trim(), artist.trim(), album.trim(), year.trim(), comment.trim(), track, genre);
    }

    private ByteBuffer readBuffer(FileChannel channel, long position, int size) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(size);
        channel.position(position);

        int bytesRead = channel.read(buffer);
        if (bytesRead != size) {
            LOGGER.warning(() -> String.format(
                    "Konnte nicht alle Bytes lesen. Erwartet: %d, Gelesen: %d",
                    size,
                    bytesRead
            ));
            return null;
        }

        buffer.flip();
        return buffer;
    }

    private String readString(ByteBuffer buffer, int length) {
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    public record ID3v2Tag(int majorVersion, int minorVersion, int flags, int tagSize, Map<String, String> frames) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder()
                    .append("Version: ").append(majorVersion).append('.').append(minorVersion).append('\n')
                    .append("Größe: ").append(tagSize).append(" Bytes\n");

            if ((flags & TagConstants.UNSYNCHRONISATION) != 0) sb.append("Unsynchronisation ist gesetzt\n");
            if ((flags & TagConstants.EXTENDED_HEADER) != 0) sb.append("Erweiterter Header ist vorhanden\n");
            if ((flags & TagConstants.EXPERIMENTAL) != 0) sb.append("Experimentelles Tag\n");

            frames.forEach((key, value) -> sb.append(key).append(": ").append(value).append('\n'));

            return sb.toString();
        }
    }

    public record ID3v1Tag(
            String title,
            String artist,
            String album,
            String year,
            String comment,
            int track,
            int genre
    ) {
        @Override
        public String toString() {
            return String.format("""
                    Titel: %s
                    Künstler: %s
                    Album: %s
                    Jahr: %s
                    Kommentar: %s
                    Track: %d
                    Genre: %d""",
                    title, artist, album, year, comment, track, genre);
        }
    }

    private record Frame(String id, String value) {}

    public static void main(String[] args) {
        if (args.length == 0) {
            LOGGER.severe("Bitte geben Sie den Pfad zur MP3-Datei als Argument an.");
            System.exit(1);
        }

        try {
            ID3TagReader reader = new ID3TagReader(args[0]);
            System.out.println(reader.readTags());
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Fehler beim Lesen der MP3-Datei", e);
            System.exit(1);
        }
    }
}
