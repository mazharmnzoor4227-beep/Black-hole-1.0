from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
import time

class Fixture(SimpleHTTPRequestHandler):
    def copyfile(self, source, outputfile):
        while chunk := source.read(8192):
            outputfile.write(chunk)
            outputfile.flush()
            time.sleep(.1)

ThreadingHTTPServer(('0.0.0.0', 8765), Fixture).serve_forever()
