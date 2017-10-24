package com.drogatkin.mailbee;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.aldan3.util.Stream;

public class MIMEMessage {
	public static final String SUBJECT = "subject";
	public static final String FROM = "from";
	
	public Map<String, Collection<String>> headers;

	public Collection<Part> parts;

	public String body;

	enum ParStat {
		hCR, hLN, hSt, hHd, hVl, eoH, hIl, hVB, cPl, cCR, cLF, cDs, cEO, cCB, cRB, cBs, eFD, eSD, eCR,
	}

	static final String BOUNDARY_LIM = "boundary=";

	static class ParsingContext {
		byte[] parsBuf;
		int lastPP;
		int limitP;

		@Override
		public String toString() {
			return "ParsingContext [lastPP=" + lastPP + ", limitP=" + limitP + ", ="
					+ (parsBuf == null ? "NULL" : new String(parsBuf, lastPP, limitP)) + "]";
		}

	}

	static boolean debug = false;

	public static MIMEMessage parse(InputStream mesStream) throws IOException {
		MIMEMessage res = new MIMEMessage();
		byte buf[] = new byte[16 * 1024];
		ParsingContext pc = new ParsingContext();
		pc.parsBuf = buf;
		
		res.headers = parseHeaders(pc, mesStream);
		res.parts = new ArrayList<>();
		boolean canntParse = true;
		String contentType = res.getHeader("content-type", "  ; charset=ascii");
		String charSet = getMIMECharset(contentType);
		//System.out.printf("Content type %s%n", contentType);
		if (!contentType.isEmpty() && contentType.toLowerCase().indexOf("multipart/") >= 0) {
			String contentTypeL = contentType.toLowerCase();
			int bp = contentTypeL.indexOf(BOUNDARY_LIM);
			//System.out.printf("bound ind %d in %s of %s%n", bp, contentTypeL, BOUNDARY_LIM);
			if (bp >= 0) {
				String boundary = contentType.substring(bp + BOUNDARY_LIM.length());
				bp = boundary.indexOf(';');
				if (bp > 0)
					boundary = boundary.substring(0, bp);
				boundary = unquote(boundary.trim());
				//System.out.printf("boundary: %s, lpp: %d, lim: %d%n", boundary, pc.lastPP, pc.limitP);
				ByteArrayOutputStream bos = new ByteArrayOutputStream();
				readToBoundary(pc, mesStream, bos, boundary);
				res.body = bos.toString();
				boolean moreParts = true;
				do {
					Part p = parsePart(pc, mesStream, boundary);
					moreParts = !p.last;
					res.parts.add(p);
				} while (moreParts);
				canntParse = false;
			} else {
				// warning no boundary found for multipart
			}
		}
		if (canntParse) {
			ByteArrayOutputStream bos = new ByteArrayOutputStream();
			if (pc.lastPP < pc.limitP) {
				bos.write(pc.parsBuf, pc.lastPP, pc.limitP - pc.lastPP);
			}
			Stream.copyStream(mesStream, bos);
			if ("quoted-printable".equalsIgnoreCase(res.getHeader("Content-Transfer-Encoding", "").trim())) {
				res.body = Stream.streamToString(
						new MIMEUtil.QDecoderStream(new ByteArrayInputStream(bos.toByteArray())), charSet, 0); // 1024 * 500
			} else { // TODO check for binary content 
				res.body = Stream.streamToString(new ByteArrayInputStream(bos.toByteArray()), charSet, 0);
			}
		}
		return res;
	}
	
	static Part parsePart(ParsingContext pc, InputStream mesStream, String boundary) throws IOException {
		Part p = new Part();
		p.headers = parseHeaders(pc, mesStream);
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		String contentType = p.getHeader("content-type", "  ; charset=ascii");
		String charSet = getMIMECharset(contentType);
		if (contentType.toLowerCase().indexOf("multipart/") >= 0) {
			p.parts = new ArrayList<>();
		     String PartBoundary = getBoundary(contentType);
		     Part p1 = null;
		     readToBoundary(pc, mesStream, bos, PartBoundary);
		     p.body = bos.toString();
		     do {
		    	 p1 = parsePart(pc, mesStream, PartBoundary);
		    	 p.parts.add(p1);
		     } while(!p1.last);
		     p.last = !readToBoundary(pc, mesStream, bos, boundary); // should be end
		} else {
			p.last = !readToBoundary(pc, mesStream, bos, boundary);
			if ("quoted-printable".equalsIgnoreCase(p.getHeader("Content-Transfer-Encoding", "").trim())) {
				//System.out.println("*****************************\r\n"+bos.toString());
				p.body = Stream.streamToString(
						new MIMEUtil.QDecoderStream(new ByteArrayInputStream(bos.toByteArray())), charSet, 0);
			} else {
				if ("binary".equalsIgnoreCase(charSet)) {
					// TODO maybe encode to BASE64??
					p.body = Base64.getEncoder().encodeToString(bos.toByteArray());
				} else
					p.body = bos.toString(charSet);
			}
		}
		return p;
	}
	
	static String getBoundary(String contentType) {
		String contentTypeL = contentType.toLowerCase();
		int bp = contentTypeL.indexOf(BOUNDARY_LIM);
		//System.out.printf("bound ind %d in %s of %s%n", bp, contentTypeL, BOUNDARY_LIM);
		if (bp >= 0) {
			String boundary = contentType.substring(bp + BOUNDARY_LIM.length());
			bp = boundary.indexOf(';');
			if (bp > 0)
				boundary = boundary.substring(0, bp);
			return unquote(boundary.trim());
		}
		return null;
	}

	public static String getMIMECharset(String header) {
		int cp = header.toLowerCase().indexOf("charset=");
		if (cp < 0)
			return "ASCII";
		String charset = header.substring(cp + "charset=".length()).trim();
		cp = charset.indexOf(';');
		if (cp > 0)
			charset = charset.substring(0, cp);
		if (charset.startsWith("3D"))
			charset = charset.substring(2); // work around of some mailers bug
		return MIMEUtil.javaCharset(unquote(charset));
	}

	public static String unquote(String s) {
		if (s.startsWith("\""))
			s = s.substring(1);
		if (s.endsWith("\""))
			s = s.substring(0, s.length() - 1);
		return s;
	}

	public static void appendHeader(Map<String, Collection<String>> headers, String name, String value) {
		name = name.toLowerCase();
		Collection<String> values;
		if (headers.containsKey(name))
			values = headers.get(name);
		else {
			values = new ArrayList<>();
			headers.put(name, values);
		}
		if (value != null)
			values.add(value);
	}

	public static Map<String, Collection<String>> parseHeaders(ParsingContext pc, InputStream mesStream)
			throws IOException {
		// TODO make it perhaps smarter and if first line not recognizable as a header, then return and
		// make rest read as body
		Map<String, Collection<String>> result = new HashMap<>();
		ParStat st = ParStat.hHd;

		if (pc.parsBuf == null) {
			pc.parsBuf = new byte[16 * 1024];
			pc.lastPP = 0;
			pc.limitP = 0;
		}
		int lim = pc.limitP;
		int lpp = pc.lastPP;
		byte buf[] = pc.parsBuf;
		byte[] hn = new byte[0];
		byte[] hv = new byte[0];
		int l;
		parse_header: do {
			if (lpp >= lim) {
				l = mesStream.read(buf);
				if (l <= 0)
					break;
				lpp = 0;
				lim = lpp + l;
			}
			for (int p = lpp; p < lim; p++) {
				//System.out.print(new String(buf, p, 1));
				switch (st) {
				case hSt:
					if ((buf[p] & 255) == ' ' || (buf[p] & 255) == 9) { // \t
						st = ParStat.hVl;
						lpp = p;
					} else if ((buf[p] & 255) == '\r') {
						st = ParStat.hLN;
						//System.out.print("---");
					} else {
						lpp = p;
						st = ParStat.hHd;
						if (hn.length > 0) {
							appendHeader(result, new String(hn), new String(hv));
							hn = new byte[0];
							hv = new byte[0];
						}
					}
					break;
				case hHd:
					if ((buf[p] & 255) == '\r' || (buf[p] & 255) == '\n') {
						// illegal header line
						st = ParStat.hIl;
						hn = concat(hn, Arrays.copyOfRange(buf, lpp, p));
						// TODO if result.isEmpty() -> not MIME, return empty headers, and let read rest as plain body
						appendHeader(result, new String(hn), null);
						lpp = p + 1;
					} else if ((buf[p] & 255) == ':') {
						hn = concat(hn, Arrays.copyOfRange(buf, lpp, p));
						st = ParStat.hVB;
						lpp = p + 1;
					}
					break;
				case hIl:
					if ((buf[p] & 255) == '\n')
						st = ParStat.hLN;
					else if ((buf[p] & 255) != '\r' && (buf[p] & 255) != ':') {
						st = ParStat.hHd;
						lpp = p;
					}
					break;
				case hVB:
					if ((buf[p] & 255) != ' ' && (buf[p] & 255) != 9) {
						st = ParStat.hVl;
						lpp = p;
					}
					//break;
				case hVl:
					if ((buf[p] & 255) == '\r') {
						hv = concat(hv, Arrays.copyOfRange(buf, lpp, p));
						lpp = p + 1;
						st = ParStat.hCR;
					} else if ((buf[p] & 255) == '\n') {
						hv = concat(hv, Arrays.copyOfRange(buf, lpp, p));
						lpp = p + 1;
						st = ParStat.hSt;
					}
					break;
				case hCR:
					if ((buf[p] & 255) == '\n')
						st = ParStat.hSt;
					else
						st = ParStat.hIl;
					break;
				case hLN:
					if ((buf[p] & 255) == '\n') {
						st = ParStat.eoH;
						lpp = p + 1;
						if (hn.length > 0) {
							appendHeader(result, new String(hn), new String(hv));
							break parse_header;
						}
					} else
						st = ParStat.hSt;
					break;
				case eoH:

					break;
				}
			} // end for
				// adding remaining
			if (st == ParStat.hHd) {
				hn = concat(hn, Arrays.copyOfRange(buf, lpp, lim));
			} else if (st == ParStat.hVl) {
				hv = concat(hv, Arrays.copyOfRange(buf, lpp, lim));
			}
			lim = lpp = 0;
		} while (st != ParStat.eoH);
		pc.lastPP = lpp;
		pc.limitP = lim;
		return result;
	}

	public static boolean readToBoundary(ParsingContext pc, InputStream mesStream, OutputStream content,
			String boundary) throws IOException {
		ParStat st = ParStat.cLF;
		int lim = pc.limitP;
		int lpp = pc.lastPP;
		int bl = boundary.length();
		int bc = 0;
		byte[] bd = new byte[0];
		boolean heldCRLFD = false, heldCR = false;
		byte buf[] = pc.parsBuf;
		do {
			if (lpp >= lim) {
				int l = mesStream.read(buf);
				if (l <= 0)
					break;
				lpp = 0;
				lim = lpp + l;
			}
			for (int p = lpp; p < lim; p++) {
				if (debug)
					System.out.println(st);
				//System.out.print(new String(buf, p, 1));
				switch (st) {
				case cPl:
					if ((buf[p] & 255) == '\r')
						st = ParStat.cCR;
					break;
				case cCR:
					if ((buf[p] & 255) == '\n')
						st = ParStat.cLF;
					else {
						st = ParStat.cPl;
					}
					break;
				case cLF:
					if (debug)
						System.out.println(new String(buf, p, 1));
					if ((buf[p] & 255) == '-') {
						st = ParStat.cDs;
					} else if ((buf[p] & 255) == '\r') {
						st = ParStat.cCR;
					} else
						st = ParStat.cPl;
					break;
				case cDs:
					if ((buf[p] & 255) == '-') {
						//System.out.println("boundary de ");
						st = ParStat.cBs;
						if (lpp < p - 4) {
							if (heldCR) {
								content.write('\r');
								heldCR = false;
							}
							content.write(buf, lpp, p - lpp - 3); // do not write CRLF-- yet
							//System.out.print("B****>["+lpp+","+p+"]"+new String(buf, lpp, p-lpp-3));
						}// else {
							//System.out.println("!!!!No dumping buf content lpp "+lpp+", pos "+p);
						//}
						bd = new byte[0];
						lpp = p + 1;
						bc = 1;
					} else if ((buf[p] & 255) == '\r') {
						st = ParStat.cCR;
					} else {
						if (heldCRLFD) {
							content.write('\r');
							content.write('\n');
							content.write('-');
							heldCRLFD = false;
						}
						st = ParStat.cPl;
					}
					break;
				case cBs:
					//System.out.print((char)buf[p]);
					if ((buf[p] & 255) == '\n') {
						content.write('\r');
						content.write('\n');
						content.write("--".getBytes());
						st = ParStat.cPl;
					} else if ((buf[p] & 255) == '\r') {
						content.write('\r');
						content.write('\n');
						content.write("--".getBytes());
						st = ParStat.cCR;
					} else if (bc == bl) {
						bd = concat(bd, Arrays.copyOfRange(buf, lpp, p + 1));
						//System.out.printf("compare %n%s%n%s%n", boundary, new String(bd) );
						if (boundary.equals(new String(bd))) {
							//System.out.println("match "+buf[p]);
							st = ParStat.cCB;
						} else {
							content.write('\r');
							content.write('\n');
							content.write("--".getBytes());
							content.write(bd);
							st = ParStat.cPl;
							lpp = p + 1;
						}
					} else
						bc++;
					break;
				case cCB:
					if ((buf[p] & 255) == '\r') {
						st = ParStat.cRB;
					} else if ((buf[p] & 255) == '-') {
						//System.out.println("end -");
						st = ParStat.eFD;
					} else {
						content.write('\r');
						content.write('\n');
						content.write(bd);
						st = ParStat.cPl;
						lpp = p;
					}
					break;
				case cRB:
					if ((buf[p] & 255) == '\n') {
						st = ParStat.cEO;
						//System.out.printf("found ending bdr %d %d%n", lpp, lim);
						lpp = p + 1;
					}
					break;
				case cEO:
					break;
				case eFD:
					if ((buf[p] & 255) == '-') {
						st = ParStat.eSD;
						//System.out.println("end --");
					} else if ((buf[p] & 255) == '\r')
						st = ParStat.cCR;
					else
						st = ParStat.cPl;
					break;
				case eSD:
					if ((buf[p] & 255) == '\r')
						st = ParStat.eCR;
					else
						st = ParStat.cPl;
					break;
				case eCR:
					if ((buf[p] & 255) == '\n') {
						st = ParStat.cEO;
						return false;
					} else
						st = ParStat.cPl;
				}
				if (st == ParStat.cEO) {
					//System.out.printf("pos %d lim %d%n", p, lim);
					break;
				}
			}
			if (false) {
				System.out.println("State at end of buff:" + st);
				if (st == ParStat.cBs) {
					System.out.println(new String(bd) + " at " + bc + " " + new String(buf, lpp, lim - lpp));
				} //else
					//System.out.println("rest: "+new String(buf, lpp, lim - lpp));
			}
			if (heldCR) {
				content.write('\r');
				heldCR = false;
			}
			 
			if (st == ParStat.cDs) {
				content.write(buf, lpp, lim - lpp - 3);
				heldCRLFD = true;
				//System.out.print("E****>["+lpp+","+lim+"]"+new String(buf, lpp, lim-lpp-3));
			} else if (st == ParStat.cBs) {
				bd = concat(bd, Arrays.copyOfRange(buf, lpp, lim));
			} else if (st == ParStat.cPl || st == ParStat.cLF) {
				content.write(buf, lpp, lim - lpp);
				//System.out.print("E****>["+lpp+","+lim+"]"+new String(buf, lpp, lim-lpp));
			} else if (st == ParStat.cCR) {
				content.write(buf, lpp, lim - lpp - 1);
				heldCR = true;
			}
			//else if( st == ParStat.cLF )
				//content.write(buf, lpp, lim - lpp - 2);
			if (st != ParStat.cEO)
				lim = lpp = 0;
			//if(p == lim)
				//lim = lpp = 0;
		} while (st != ParStat.cEO);
		pc.lastPP = lpp;
		pc.limitP = lim;
		return st == ParStat.cEO;
	}

	public <T> T getHeader(String name, T defVal) {
		name = name.toLowerCase();
		Collection<String> values = headers.get(name);
		if (values == null || values.size() == 0)
			return defVal;
		String value = values.iterator().next();
		if (defVal instanceof Number) {
			if (defVal instanceof Integer) {
				return (T) new Integer(value);
			} else if (defVal instanceof Long) {
				return (T) new Long(value);
			} else if (defVal instanceof Double)
				return (T) new Double(value);
		} else if (defVal instanceof Date) {
			try {
				return (T) new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(value);
			} catch (ParseException e) {
				return defVal;
			}
		}
		return (T) value;
	}

	public String getBodyDecoded() {
		return body;
	}

	/**
	 * returns a part with index marching specified content type
	 * 
	 * @param contentType
	 * @param index
	 * @return
	 */
	public Part getPart(String contentType, int index) {
		int i = 0;
		for (Part part : parts) {
			String ct = part.getHeader("content-type", "").toLowerCase();
			if (part.parts != null) {
				Part part1 = part.getPart(contentType, index);
				if (part1 != null && index == i)
					return part1;
			}
			if (ct.indexOf(contentType) == 0) {
				if (index == i)
					return part;
				i++;
			}
		}
		return null;
	}

	public static class Part extends MIMEMessage {
		public boolean last;

	}

	static public byte[] concat(byte[]... bufs) {
		if (bufs.length == 0)
			return null;
		if (bufs.length == 1)
			return bufs[0];
		for (int i = 0; i < bufs.length - 1; i++) {
			byte[] res = Arrays.copyOf(bufs[i], bufs[i].length + bufs[i + 1].length);
			System.arraycopy(bufs[i + 1], 0, res, bufs[i].length, bufs[i + 1].length);
			bufs[i + 1] = res;
		}
		return bufs[bufs.length - 1];
	}

	@Override
	public String toString() {
		return "MIMEMessage [body='" + body + "', headers=" + headers + ", parts=" + parts + "]";
	}

	public static void main(String... args) {
		System.out.printf("MIME test%n");
		//System.out.printf("Concat test %s%n",
		//	new String(concat("Hello".getBytes(), " ".getBytes(), "Dear ".getBytes(), "Friend".getBytes())));
		try {
			MIMEMessage m;
			System.out.printf("Parsed %s as %n%s%n", args[0], m =parse(new FileInputStream(args[0])));
			//System.out.printf("Subject %s\nDecoded:%s%n%s%n", m.getHeader("subject", ""), MIMEUtil.decodeText(m.getHeader("subject", "")),
				//	DataConv.bytesToHex(MIMEUtil.decodeText(m.getHeader("subject", "")).getBytes("UTF-8")));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
