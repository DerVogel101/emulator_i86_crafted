package dervogel101.de.i86.runtime;

import dervogel101.de.emulator_i86_crafted.server.storage.PunchcardLibrary;
import java.io.*;
import java.nio.file.*;

final class PunchcardSelfTest {
    static void run() throws Exception {
        Path folder=Files.createTempDirectory("punchcard-library");
        try {
            var library=new PunchcardLibrary(folder);
            var blank=PunchcardDeck.blank(); library.save(blank); library.claim(blank.id(),"first writer");
            new PunchcardLibrary(folder).claim(blank.id(),"first writer");
            try { new PunchcardLibrary(folder).claim(blank.id(),"second writer"); throw new AssertionError("Duplicate writer owner accepted"); } catch (IOException expected) { }
            var deck=PunchcardDeck.blank().row(0,0,new byte[32]).seal();
            var first=library.save(deck);
            var duplicate=library.save(PunchcardDeck.blank().row(0,0,new byte[32]).seal());
            assert duplicate.id().equals(first.id());
            var reopened=new PunchcardLibrary(folder); assert reopened.read(first.id()).hash().equals(first.hash());
            var conflicting=new PunchcardDeck(first.id(),new byte[32],new byte[512],true,false);
            try { reopened.save(conflicting); throw new AssertionError("Conflicting UUID accepted"); } catch (IOException expected) { }
            Files.write(folder.resolve(first.id()+".pdeck"),new byte[]{1,2,3});
            try { reopened.read(first.id()); throw new AssertionError("Corrupt deck accepted"); } catch (IOException expected) { }
            assert Files.size(folder.resolve(first.id()+".pdeck"))==3;
        } finally { try (var files=Files.list(folder)) { for (Path path : files.toList()) Files.delete(path); } Files.delete(folder); }
    }
}
