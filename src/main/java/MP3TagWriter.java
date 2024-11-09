import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.id3.ID3v23Tag;

/* Wird nur benötigt wenn Cover-Bilder hinzugefügt werden sollen
import org.jaudiotagger.tag.FieldDataInvalidException;
import org.jaudiotagger.tag.id3.valuepair.ImageFormats;
import org.jaudiotagger.tag.images.Artwork;
import org.jaudiotagger.tag.images.ArtworkFactory;
 */

import java.io.File;
import java.io.IOException;

// Beispiel für das Schreiben von Tags:
// java -cp target/mp3-fat.jar MP3TagWriter resources/Distant_Wonders.mp3

public class MP3TagWriter {
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Verwendung: java -cp target/mp3-fat.jar MP3TagWriter <MP3-Dateipfad>");
            System.exit(1);
        }

        String filePath = args[0];
        File mp3File = new File(filePath);

        if (!mp3File.exists()) {
            System.out.println("Die Datei " + filePath + " wurde nicht gefunden.");
            return;
        }

        try {
            // Lade die MP3-Datei
            AudioFile audioFile = AudioFileIO.read(mp3File);

            // Erstelle ein ID3v2.3-Tag, wenn es nicht existiert
            Tag tag = audioFile.getTag();
            if (!(tag instanceof ID3v23Tag)) {
                tag = new ID3v23Tag();
                audioFile.setTag(tag);
            }

            // Feste Beispielwerte für ID3-Tags
            tag.setField(FieldKey.ARTIST, "Test Artist");
            tag.setField(FieldKey.ALBUM, "Test Album");
            tag.setField(FieldKey.TITLE, "Distant Wonders");
            tag.setField(FieldKey.YEAR, "2024");
            tag.setField(FieldKey.GENRE, "Pop");

            // Urheberrechtsinformationen
            tag.setField(FieldKey.COPYRIGHT, "© 2024 MyMusicLabel");
            tag.setField(FieldKey.ISRC, "US-S1Z-99-00001");
            tag.setField(FieldKey.RECORD_LABEL, "MyMusicPublisher");
            tag.setField(FieldKey.PRODUCER, " John Doe");

            // Speichern der Änderungen
            AudioFileIO.write(audioFile);

            System.out.println("ID3-Tags erfolgreich zu " + filePath + " hinzugefügt.");
        } catch (Exception e) {
            System.err.println("Fehler beim Schreiben der ID3-Tags: " + e.getMessage());
            e.printStackTrace();
        }
    }
}