package com.drogatkin.mailbee;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;

import org.aldan3.model.ProcessException;
import org.aldan3.util.DataConv;

public class MIMEUtil {

	// This class cannot be instantiated
	private MIMEUtil() {
	}

	public static final int ALL = -1;

	private static final boolean decodeStrict = true;

	private static final boolean encodeEolStrict = false;

	private static final boolean foldText = true;

	/**
	 * Decode "unstructured" headers, that is, headers that are defined as
	 * '*text' as per RFC 822.
	 * <p>
	 *
	 * The string is decoded using the algorithm specified in RFC 2047, Section
	 * 6.1. If the charset-conversion fails for any sequence, an
	 * UnsupportedEncodingException is thrown. If the String is not an RFC 2047
	 * style encoded header, it is returned as-is
	 * <p>
	 *
	 * Example of usage:
	 * <p>
	 * <blockquote>
	 * 
	 * <pre>
	 *
	 *  MimePart part = ...
	 *  String rawvalue = null;
	 *  String  value = null;
	 *  try {
	 *    if ((rawvalue = part.getHeader("X-mailer")[0]) != null)
	 *      value = MimeUtility.decodeText(rawvalue);
	 *  } catch (UnsupportedEncodingException e) {
	 *      // Don't care
	 *      value = rawvalue;
	 *  } catch (MessagingException me) { }
	 *
	 *  return value;
	 *
	 * </pre>
	 * 
	 * </blockquote>
	 * <p>
	 *
	 * @param etext
	 *            the possibly encoded value
	 * @exception UnsupportedEncodingException
	 *                if the charset conversion failed.
	 */
	public static String decodeText(String etext) throws UnsupportedEncodingException {
		/*
		 * We look for sequences separated by "linear-white-space".
		 * (as per RFC 2047, Section 6.1)
		 * RFC 822 defines "linear-white-space" as SPACE | HT | CR | NL.
		 */
		String lwsp = " \t\n\r";
		StringTokenizer st;

		/*
		 * First, lets do a quick run thru the string and check
		 * whether the sequence "=?"  exists at all. If none exists,
		 * we know there are no encoded-words in here and we can just
		 * return the string as-is, without suffering thru the later 
		 * decoding logic. 
		 * This handles the most common case of unencoded headers 
		 * efficiently.
		 */
		if (etext.indexOf("=?") == -1)
			return etext;

		// Encoded words found. Start decoding ...

		st = new StringTokenizer(etext, lwsp, true);
		StringBuffer sb = new StringBuffer(); // decode buffer
		StringBuffer wsb = new StringBuffer(); // white space buffer
		Word prevEncoded = null;

		while (st.hasMoreTokens()) {
			char c;
			String s = st.nextToken();
			// If whitespace, append it to the whitespace buffer
			if (((c = s.charAt(0)) == ' ') || (c == '\t') || (c == '\r') || (c == '\n'))
				wsb.append(c);
			else {
				// Check if token is an 'encoded-word' ..
				Word word;
				try {
					word = decodeWord(s);
					if (prevEncoded != null) { 
						// TODO if appended with same encoding
						prevEncoded.append(word);
						//prevEncoded.debug();
					} else {
						prevEncoded = word;
						// Yes, this IS an 'encoded-word'.
						if (wsb.length() > 0) {
							// if the previous word was also encoded, we
							// should ignore the collected whitespace. Else
							// we include the whitespace as well.
							sb.append(wsb);
						}
					}
				} catch (ProcessException pex) {
					if (prevEncoded != null) {
						sb.append(prevEncoded);
						prevEncoded = null;
					}
					// This is NOT an 'encoded-word'.
					// possibly decode inner encoded words
					if (!decodeStrict) {
						String dword = decodeInnerWords(s);
						if (dword != s) {
							// if a different String object was returned,
							// decoding was done.
							if (prevEncoded != null && s.startsWith("=?")) {
								// encoded followed by encoded,
								// throw away whitespace between
							} else {
								// include collected whitespace ..
								if (wsb.length() > 0)
									sb.append(wsb);
							}
							// did original end with encoded?
							if ( s.endsWith("?=")) {
								prevEncoded = new Word();
							}
							sb.append(dword);
						} else {
							// include collected whitespace ..
							if (wsb.length() > 0)
								sb.append(wsb);
							prevEncoded = null;
						}
					} else {
						// include collected whitespace ..
						if (wsb.length() > 0)
							sb.append(wsb);
						prevEncoded = null;
					}
				}
				//sb.append(word); // append the actual word
				wsb.setLength(0); // reset wsb for reuse
			}
		}
		if (prevEncoded != null)
			sb.append(prevEncoded);
		sb.append(wsb); // append trailing whitespace
		return sb.toString();
	}

	static class QDecoderStream extends FilterInputStream {
		byte[] buf = new byte[2];

		protected QDecoderStream(InputStream ins) {
			super(ins);
		}

		@Override
		public int read(byte[] buf, int pos, int len) throws IOException {
			if (pos+len > buf.length)
				throw new IllegalArgumentException("Buffer size "+buf.length+" isn't sufficient to accomodate "+len+" from "+pos);
			for (int i = 0; i < len; i++) {
				int c = read();
				if (c < 0)
					return i;
				buf[pos + i] = (byte) (c & 255);
			}
			return len;
		}

		@Override
		public int read(byte[] buf) throws IOException {
			return read(buf, 0, buf.length);
		}

		public int read() throws IOException {
			do {
				int c = in.read(); //System.out.print((char)c);
				if (c == '_') // Return '_' as ' '
					return ' ';
				else if (c == '=') {
					// QP Encoded atom. Get the next two bytes ..
					c = in.read();
					if (c == -1)
						return -1;
					buf[0] = (byte) c;
					c = in.read();
					if (c == -1)
						return c;
					buf[1] = (byte) c;
					// .. and decode them
					if ((buf[0] & 255) == '\r' && (buf[1] & 255) == '\n')
						continue;
					try {
						return Integer.parseInt(new String(buf, "ASCII"), 16);
					} catch (NumberFormatException nex) {
						throw new IOException(
								"QDecoder: Error in QP stream :" + nex.getMessage() +" :"+ buf[0] + "/" + buf[1]);
					}
				} else
					return c;
			} while (true);
		}

	}
	
	static class Word {
		byte[] word;
		String charset;
		
		Word append(Word w) {
			if (word == null)
				word = w.word;
			else if (w.word != null && w.word.length > 0)
				append(w.word);
			return this;	
		}
		
		Word append(String s) throws UnsupportedEncodingException {
			if (s.length() > 0)
				append(s.getBytes(charset));
			return this;
		}
		
		private void append(byte[] bs) {
			word = Arrays.copyOf(word, word.length + bs.length);
			System.arraycopy(bs, 0, word, word.length - bs.length, bs.length);
		}
		
		@Override
		public String toString() {
			try {
				return word==null?"":new String(word, charset);
			} catch (UnsupportedEncodingException e) {
				return e.toString();
			}
		}
		
		void debug(String mark) {
			System.out.printf("%s: %s%n", mark, DataConv.bytesToHex(word));
		}
	}

	/**
	 * The string is parsed using the rules in RFC 2047 and RFC 2231 for parsing
	 * an "encoded-word". If the parse fails, a ParseException is thrown.
	 * Otherwise, it is transfer-decoded, and then charset-converted into
	 * Unicode. If the charset-conversion fails, an UnsupportedEncodingException
	 * is thrown.
	 * <p>
	 *
	 * @param eword
	 *            the encoded value
	 * @exception ProcessException
	 *                if the string is not an encoded-word as per RFC 2047 and
	 *                RFC 2231.
	 * @exception UnsupportedEncodingException
	 *                if the charset conversion failed.
	 */
	// TODO make it returning byte[] and do final decoding concatenating returned byte[]
	public static Word decodeWord(String eword) throws ProcessException, UnsupportedEncodingException {

		if (!eword.startsWith("=?")) // not an encoded word
			throw new ProcessException("encoded word does not start with \"=?\": " + eword);

		// get charset
		int start = 2;
		int pos;
		if ((pos = eword.indexOf('?', start)) == -1)
			throw new ProcessException("encoded word does not include charset: " + eword);
		String charset = eword.substring(start, pos);
		int lpos = charset.indexOf('*'); // RFC 2231 language specified?
		if (lpos >= 0) // yes, throw it away
			charset = charset.substring(0, lpos);
		charset = javaCharset(charset);

		// get encoding
		start = pos + 1;
		if ((pos = eword.indexOf('?', start)) == -1)
			throw new ProcessException("encoded word does not include encoding: " + eword);
		String encoding = eword.substring(start, pos);

		// get encoded-sequence
		start = pos + 1;
		if ((pos = eword.indexOf("?=", start)) == -1)
			throw new ProcessException("encoded word does not end with \"?=\": " + eword);
		/*
		 * XXX - should include this, but leaving it out for compatibility...
		 *
		if (decodeStrict && pos != eword.length() - 2)
		throw new ParseException(
			"encoded word does not end with \"?=\": " + eword););
		 */
		String word = eword.substring(start, pos);

		try {
			Word decodedWord = new Word();
			decodedWord.charset = charset;
			if (word.length() > 0) {
				// Extract the bytes from word
				ByteArrayInputStream bis = new ByteArrayInputStream(word.getBytes("ASCII"));

				// Get the appropriate decoder
				InputStream is;
				if (encoding.equalsIgnoreCase("B"))
					is = Base64.getMimeDecoder().wrap(bis);
				else if (encoding.equalsIgnoreCase("Q"))
					is = new QDecoderStream(bis);
				else
					throw new UnsupportedEncodingException("unknown encoding: " + encoding);

				// For b64 & q, size of decoded word <= size of word. So
				// the decoded bytes must fit into the 'bytes' array. This
				// is certainly more efficient than writing bytes into a
				// ByteArrayOutputStream and then pulling out the byte[]
				// from it.
				int count = bis.available();
				byte[] bytes = new byte[count];
				// count is set to the actual number of decoded bytes 
				count = is.read(bytes, 0, count);

				// Finally, convert the decoded bytes into a String using
				// the specified charset
				//System.out.printf("Bytes: %s%n", DataConv.bytesToHex(bytes));
				decodedWord.word = count <= 0 ? new byte[0] : Arrays.copyOf(bytes, count);
			} else {
				// no characters to decode, return empty string
				decodedWord.word = new byte[0];
			}
			if (pos + 2 < eword.length()) {
				// there's still more text in the string
				String rest = eword.substring(pos + 2);
				if (!decodeStrict)
					rest = decodeInnerWords(rest);
				decodedWord .append(rest);
			} //System.out.printf("Decoded %s%n", decodedWord);
			return decodedWord;
		} catch (UnsupportedEncodingException uex) {
			// explicitly catch and rethrow this exception, otherwise
			// the below IOException catch will swallow this up!
			throw uex;
		} catch (IOException ioex) {
			// Shouldn't happen.
			throw new ProcessException(ioex.toString());
		} catch (IllegalArgumentException iex) {
			/* An unknown charset of the form ISO-XXX-XXX, will cause
			 * the JDK to throw an IllegalArgumentException ... Since the
			 * JDK will attempt to create a classname using this string,
			 * but valid classnames must not contain the character '-',
			 * and this results in an IllegalArgumentException, rather than
			 * the expected UnsupportedEncodingException. Yikes
			 */
			throw new UnsupportedEncodingException(charset);
		}
	}

	/**
	 * Look for encoded words within a word. The MIME spec doesn't allow this,
	 * but many broken mailers, especially Japanese mailers, produce such
	 * incorrect encodings.
	 */
	private static String decodeInnerWords(String word) throws UnsupportedEncodingException {
		int start = 0, i;
		StringBuffer buf = new StringBuffer();
		while ((i = word.indexOf("=?", start)) >= 0) {
			buf.append(word.substring(start, i));
			// find first '?' after opening '=?' - end of charset
			int end = word.indexOf('?', i + 2);
			if (end < 0)
				break;
			// find next '?' after that - end of encoding
			end = word.indexOf('?', end + 1);
			if (end < 0)
				break;
			// find terminating '?='
			end = word.indexOf("?=", end + 1);
			if (end < 0)
				break;
			String s = word.substring(i, end + 2);
			try {
				s = decodeWord(s).toString();
			} catch (ProcessException pex) {
				// ignore it, just use the original string
			}
			buf.append(s);
			start = end + 2;
		}
		if (start == 0)
			return word;
		if (start < word.length())
			buf.append(word.substring(start));
		return buf.toString();
	}

	/**
	 * A utility method to quote a word, if the word contains any characters
	 * from the specified 'specials' list.
	 * <p>
	 *
	 * The <code>HeaderTokenizer</code> class defines two special sets of
	 * delimiters - MIME and RFC 822.
	 * <p>
	 *
	 * This method is typically used during the generation of RFC 822 and MIME
	 * header fields.
	 *
	 * @param word
	 *            word to be quoted
	 * @param specials
	 *            the set of special characters
	 * @return the possibly quoted word
	 * @see javax.mail.internet.HeaderTokenizer#MIME
	 * @see javax.mail.internet.HeaderTokenizer#RFC822
	 */
	public static String quote(String word, String specials) {
		int len = word.length();
		if (len == 0)
			return "\"\""; // an empty string is handled specially

		/*
		 * Look for any "bad" characters, Escape and
		 *  quote the entire string if necessary.
		 */
		boolean needQuoting = false;
		for (int i = 0; i < len; i++) {
			char c = word.charAt(i);
			if (c == '"' || c == '\\' || c == '\r' || c == '\n') {
				// need to escape them and then quote the whole string
				StringBuffer sb = new StringBuffer(len + 3);
				sb.append('"');
				sb.append(word.substring(0, i));
				int lastc = 0;
				for (int j = i; j < len; j++) {
					char cc = word.charAt(j);
					if ((cc == '"') || (cc == '\\') || (cc == '\r') || (cc == '\n'))
						if (cc == '\n' && lastc == '\r')
							; // do nothing, CR was already escaped
						else
							sb.append('\\'); // Escape the character
					sb.append(cc);
					lastc = cc;
				}
				sb.append('"');
				return sb.toString();
			} else if (c < 040 || c >= 0177 || specials.indexOf(c) >= 0)
				// These characters cause the string to be quoted
				needQuoting = true;
		}

		if (needQuoting) {
			StringBuffer sb = new StringBuffer(len + 2);
			sb.append('"').append(word).append('"');
			return sb.toString();
		} else
			return word;
	}

	/**
	 * Fold a string at linear whitespace so that each line is no longer than 76
	 * characters, if possible. If there are more than 76 non-whitespace
	 * characters consecutively, the string is folded at the first whitespace
	 * after that sequence. The parameter <code>used</code> indicates how many
	 * characters have been used in the current line; it is usually the length
	 * of the header name.
	 * <p>
	 *
	 * Note that line breaks in the string aren't escaped; they probably should
	 * be.
	 *
	 * @param used
	 *            characters used in line so far
	 * @param s
	 *            the string to fold
	 * @return the folded string
	 * @since JavaMail 1.4
	 */
	public static String fold(int used, String s) {
		if (!foldText)
			return s;

		int end;
		char c;
		// Strip trailing spaces and newlines
		for (end = s.length() - 1; end >= 0; end--) {
			c = s.charAt(end);
			if (c != ' ' && c != '\t' && c != '\r' && c != '\n')
				break;
		}
		if (end != s.length() - 1)
			s = s.substring(0, end + 1);

		// if the string fits now, just return it
		if (used + s.length() <= 76)
			return s;

		// have to actually fold the string
		StringBuffer sb = new StringBuffer(s.length() + 4);
		char lastc = 0;
		while (used + s.length() > 76) {
			int lastspace = -1;
			for (int i = 0; i < s.length(); i++) {
				if (lastspace != -1 && used + i > 76)
					break;
				c = s.charAt(i);
				if (c == ' ' || c == '\t')
					if (!(lastc == ' ' || lastc == '\t'))
						lastspace = i;
				lastc = c;
			}
			if (lastspace == -1) {
				// no space, use the whole thing
				sb.append(s);
				s = "";
				used = 0;
				break;
			}
			sb.append(s.substring(0, lastspace));
			sb.append("\r\n");
			lastc = s.charAt(lastspace);
			sb.append(lastc);
			s = s.substring(lastspace + 1);
			used = 1;
		}
		sb.append(s);
		return sb.toString();
	}

	/**
	 * Unfold a folded header. Any line breaks that aren't escaped and are
	 * followed by whitespace are removed.
	 *
	 * @param s
	 *            the string to unfold
	 * @return the unfolded string
	 * @since JavaMail 1.4
	 */
	public static String unfold(String s) {
		if (!foldText)
			return s;

		StringBuffer sb = null;
		int i;
		while ((i = indexOfAny(s, "\r\n")) >= 0) {
			int start = i;
			int l = s.length();
			i++; // skip CR or NL
			if (i < l && s.charAt(i - 1) == '\r' && s.charAt(i) == '\n')
				i++; // skip LF
			if (start == 0 || s.charAt(start - 1) != '\\') {
				char c;
				// if next line starts with whitespace, skip all of it
				// XXX - always has to be true?
				if (i < l && ((c = s.charAt(i)) == ' ' || c == '\t')) {
					i++; // skip whitespace
					while (i < l && ((c = s.charAt(i)) == ' ' || c == '\t'))
						i++;
					if (sb == null)
						sb = new StringBuffer(s.length());
					if (start != 0) {
						sb.append(s.substring(0, start));
						sb.append(' ');
					}
					s = s.substring(i);
					continue;
				}
				// it's not a continuation line, just leave it in
				if (sb == null)
					sb = new StringBuffer(s.length());
				sb.append(s.substring(0, i));
				s = s.substring(i);
			} else {
				// there's a backslash at "start - 1"
				// strip it out, but leave in the line break
				if (sb == null)
					sb = new StringBuffer(s.length());
				sb.append(s.substring(0, start - 1));
				sb.append(s.substring(start, i));
				s = s.substring(i);
			}
		}
		if (sb != null) {
			sb.append(s);
			return sb.toString();
		} else
			return s;
	}

	/**
	 * Return the first index of any of the characters in "any" in "s", or -1 if
	 * none are found.
	 *
	 * This should be a method on String.
	 */
	private static int indexOfAny(String s, String any) {
		return indexOfAny(s, any, 0);
	}

	private static int indexOfAny(String s, String any, int start) {
		try {
			int len = s.length();
			for (int i = start; i < len; i++) {
				if (any.indexOf(s.charAt(i)) >= 0)
					return i;
			}
			return -1;
		} catch (StringIndexOutOfBoundsException e) {
			return -1;
		}
	}

	/**
	 * Convert a MIME charset name into a valid Java charset name.
	 * <p>
	 *
	 * @param charset
	 *            the MIME charset name
	 * @return the Java charset equivalent. If a ssuitable mapping is not
	 *         available, the passed in charset is itself returned.
	 */
	public static String javaCharset(String charset) {
		if (mime2java == null || charset == null)
			// no mapping table, or charset parameter is null
			return charset;
		String alias = mime2java.get(charset.toLowerCase(Locale.ENGLISH));
		return alias == null ? charset : alias;
	}

	/**
	 * Convert a java charset into its MIME charset name.
	 * <p>
	 *
	 * Note that a future version of JDK (post 1.2) might provide this
	 * functionality, in which case, we may deprecate this method then.
	 *
	 * @param charset
	 *            the JDK charset
	 * @return the MIME/IANA equivalent. If a mapping is not possible, the
	 *         passed in charset itself is returned.
	 * @since JavaMail 1.1
	 */
	public static String mimeCharset(String charset) {
		if (java2mime == null || charset == null)
			// no mapping table or charset param is null
			return charset;

		String alias = (String) java2mime.get(charset.toLowerCase(Locale.ENGLISH));
		return alias == null ? charset : alias;
	}

	private static String defaultJavaCharset;
	private static String defaultMIMECharset;

	/**
	 * Get the default charset corresponding to the system's current default
	 * locale. If the System property <code>mail.mime.charset</code> is set, a
	 * system charset corresponding to this MIME charset will be returned.
	 * <p>
	 * 
	 * @return the default charset of the system's default locale, as a Java
	 *         charset. (NOT a MIME charset)
	 * @since JavaMail 1.1
	 */
	public static String getDefaultJavaCharset() {
		if (defaultJavaCharset == null) {
			/*
			 * If mail.mime.charset is set, it controls the default
			 * Java charset as well.
			 */
			String mimecs = null;
			try {
				mimecs = System.getProperty("mail.mime.charset");
			} catch (SecurityException ex) {
			} // ignore it
			if (mimecs != null && mimecs.length() > 0) {
				defaultJavaCharset = javaCharset(mimecs);
				return defaultJavaCharset;
			}

			try {
				defaultJavaCharset = System.getProperty("file.encoding", "8859_1");
			} catch (SecurityException sex) {

				class NullInputStream extends InputStream {
					public int read() {
						return 0;
					}
				}
				try (InputStreamReader reader = new InputStreamReader(new NullInputStream());) {
					defaultJavaCharset = reader.getEncoding();
				} catch (IOException e) {

				}
				if (defaultJavaCharset == null)
					defaultJavaCharset = "8859_1";
			}
		}

		return defaultJavaCharset;
	}

	/*
	 * Get the default MIME charset for this locale.
	 */
	static String getDefaultMIMECharset() {
		if (defaultMIMECharset == null) {
			try {
				defaultMIMECharset = System.getProperty("mail.mime.charset");
			} catch (SecurityException ex) {
			} // ignore it
		}
		if (defaultMIMECharset == null)
			defaultMIMECharset = mimeCharset(getDefaultJavaCharset());
		return defaultMIMECharset;
	}

	private static HashMap<String, String> mime2java;
	private static HashMap<String, String> java2mime;

	static {
		java2mime = new HashMap<>(40);
		mime2java = new HashMap<>(10);

		try {
			InputStream is = MIMEUtil.class.getResourceAsStream("/META-INF/rfc2047.charset.map");

			if (is != null) {
				try {
					BufferedReader r = new BufferedReader(new InputStreamReader(is));

					// Load the JDK-to-MIME charset mapping table
					loadMappings(r, java2mime);

					// Load the MIME-to-JDK charset mapping table
					loadMappings(r, mime2java);
				} finally {
					try {
						is.close();
					} catch (Exception cex) {
						//cex.printStackTrace();
					}
				}
			} //else
				//System.err.printf("No stream%n");
		} catch (Exception ex) {
			//ex.printStackTrace();
		}

		// If we didn't load the tables, e.g., because we didn't have
		// permission, load them manually.  The entries here should be
		// the same as the default javamail.charset.map.
		if (java2mime.isEmpty()) {
			java2mime.put("8859_1", "ISO-8859-1");
			java2mime.put("iso8859_1", "ISO-8859-1");
			java2mime.put("iso8859-1", "ISO-8859-1");

			java2mime.put("8859_2", "ISO-8859-2");
			java2mime.put("iso8859_2", "ISO-8859-2");
			java2mime.put("iso8859-2", "ISO-8859-2");

			java2mime.put("8859_3", "ISO-8859-3");
			java2mime.put("iso8859_3", "ISO-8859-3");
			java2mime.put("iso8859-3", "ISO-8859-3");

			java2mime.put("8859_4", "ISO-8859-4");
			java2mime.put("iso8859_4", "ISO-8859-4");
			java2mime.put("iso8859-4", "ISO-8859-4");

			java2mime.put("8859_5", "ISO-8859-5");
			java2mime.put("iso8859_5", "ISO-8859-5");
			java2mime.put("iso8859-5", "ISO-8859-5");

			java2mime.put("8859_6", "ISO-8859-6");
			java2mime.put("iso8859_6", "ISO-8859-6");
			java2mime.put("iso8859-6", "ISO-8859-6");

			java2mime.put("8859_7", "ISO-8859-7");
			java2mime.put("iso8859_7", "ISO-8859-7");
			java2mime.put("iso8859-7", "ISO-8859-7");

			java2mime.put("8859_8", "ISO-8859-8");
			java2mime.put("iso8859_8", "ISO-8859-8");
			java2mime.put("iso8859-8", "ISO-8859-8");

			java2mime.put("8859_9", "ISO-8859-9");
			java2mime.put("iso8859_9", "ISO-8859-9");
			java2mime.put("iso8859-9", "ISO-8859-9");

			java2mime.put("sjis", "Shift_JIS");
			java2mime.put("jis", "ISO-2022-JP");
			java2mime.put("iso2022jp", "ISO-2022-JP");
			java2mime.put("euc_jp", "euc-jp");
			java2mime.put("koi8_r", "koi8-r");
			java2mime.put("euc_cn", "euc-cn");
			java2mime.put("euc_tw", "euc-tw");
			java2mime.put("euc_kr", "euc-kr");
		}
		if (mime2java.isEmpty()) {
			mime2java.put("iso-2022-cn", "ISO2022CN");
			mime2java.put("iso-2022-kr", "ISO2022KR");
			mime2java.put("utf-8", "UTF8");
			mime2java.put("utf8", "UTF8");
			mime2java.put("ja_jp.iso2022-7", "ISO2022JP");
			mime2java.put("ja_jp.eucjp", "EUCJIS");
			mime2java.put("euc-kr", "KSC5601");
			mime2java.put("euckr", "KSC5601");
			mime2java.put("us-ascii", "ISO-8859-1");
			mime2java.put("x-us-ascii", "ISO-8859-1");
		}
	}

	private static void loadMappings(BufferedReader r, HashMap<String, String> table) {
		String currLine;

		while (true) {
			try {
				currLine = r.readLine();
			} catch (IOException ioex) {
				break; // error in reading, stop
			}

			if (currLine == null) // end of file, stop
				break;
			if (currLine.startsWith("--") && currLine.endsWith("--"))
				// end of this table
				break;

			// ignore empty lines and comments
			if (currLine.trim().length() == 0 || currLine.startsWith("#"))
				continue;

			// A valid entry is of the form <key><separator><value>
			// where, <separator> := SPACE | HT. Parse this
			StringTokenizer tk = new StringTokenizer(currLine, " \t");
			try {
				String key = tk.nextToken();
				String value = tk.nextToken();
				//System.out.printf("%s ->%s%n", key, value);
				table.put(key.toLowerCase(Locale.ENGLISH), value);
			} catch (NoSuchElementException nex) {
			}
		}
	}

	static final int ALL_ASCII = 1;
	static final int MOSTLY_ASCII = 2;
	static final int MOSTLY_NONASCII = 3;

	/**
	 * Check if the given string contains non US-ASCII characters.
	 * 
	 * @param s
	 *            string
	 * @return ALL_ASCII if all characters in the string belong to the US-ASCII
	 *         charset. MOSTLY_ASCII if more than half of the available
	 *         characters are US-ASCII characters. Else MOSTLY_NONASCII.
	 */
	static int checkAscii(String s) {
		int ascii = 0, non_ascii = 0;
		int l = s.length();

		for (int i = 0; i < l; i++) {
			if (nonascii((int) s.charAt(i))) // non-ascii
				non_ascii++;
			else
				ascii++;
		}

		if (non_ascii == 0)
			return ALL_ASCII;
		if (ascii > non_ascii)
			return MOSTLY_ASCII;

		return MOSTLY_NONASCII;
	}

	/**
	 * Check if the given byte array contains non US-ASCII characters.
	 * 
	 * @param b
	 *            byte array
	 * @return ALL_ASCII if all characters in the string belong to the US-ASCII
	 *         charset. MOSTLY_ASCII if more than half of the available
	 *         characters are US-ASCII characters. Else MOSTLY_NONASCII.
	 *
	 *         XXX - this method is no longer used
	 */
	static int checkAscii(byte[] b) {
		int ascii = 0, non_ascii = 0;

		for (int i = 0; i < b.length; i++) {
			// The '&' operator automatically causes b[i] to be promoted
			// to an int, and we mask out the higher bytes in the int 
			// so that the resulting value is not a negative integer.
			if (nonascii(b[i] & 0xff)) // non-ascii
				non_ascii++;
			else
				ascii++;
		}

		if (non_ascii == 0)
			return ALL_ASCII;
		if (ascii > non_ascii)
			return MOSTLY_ASCII;

		return MOSTLY_NONASCII;
	}

	/**
	 * Check if the given input stream contains non US-ASCII characters. Upto
	 * <code>max</code> bytes are checked. If <code>max</code> is set to
	 * <code>ALL</code>, then all the bytes available in this input stream are
	 * checked. If <code>breakOnNonAscii</code> is true the check terminates
	 * when the first non-US-ASCII character is found and MOSTLY_NONASCII is
	 * returned. Else, the check continues till <code>max</code> bytes or till
	 * the end of stream.
	 *
	 * @param is
	 *            the input stream
	 * @param max
	 *            maximum bytes to check for. The special value ALL indicates
	 *            that all the bytes in this input stream must be checked.
	 * @param breakOnNonAscii
	 *            if <code>true</code>, then terminate the the check when the
	 *            first non-US-ASCII character is found.
	 * @return ALL_ASCII if all characters in the string belong to the US-ASCII
	 *         charset. MOSTLY_ASCII if more than half of the available
	 *         characters are US-ASCII characters. Else MOSTLY_NONASCII.
	 */
	static int checkAscii(InputStream is, int max, boolean breakOnNonAscii) {
		int ascii = 0, non_ascii = 0;
		int len;
		int block = 4096;
		int linelen = 0;
		boolean longLine = false, badEOL = false;
		boolean checkEOL = encodeEolStrict && breakOnNonAscii;
		byte buf[] = null;
		if (max != 0) {
			block = (max == ALL) ? 4096 : Math.min(max, 4096);
			buf = new byte[block];
		}
		while (max != 0) {
			try {
				if ((len = is.read(buf, 0, block)) == -1)
					break;
				int lastb = 0;
				for (int i = 0; i < len; i++) {
					// The '&' operator automatically causes b[i] to 
					// be promoted to an int, and we mask out the higher
					// bytes in the int so that the resulting value is 
					// not a negative integer.
					int b = buf[i] & 0xff;
					if (checkEOL && ((lastb == '\r' && b != '\n') || (lastb != '\r' && b == '\n')))
						badEOL = true;
					if (b == '\r' || b == '\n')
						linelen = 0;
					else {
						linelen++;
						if (linelen > 998) // 1000 - CRLF
							longLine = true;
					}
					if (nonascii(b)) { // non-ascii
						if (breakOnNonAscii) // we are done
							return MOSTLY_NONASCII;
						else
							non_ascii++;
					} else
						ascii++;
					lastb = b;
				}
			} catch (IOException ioex) {
				break;
			}
			if (max != ALL)
				max -= len;
		}

		if (max == 0 && breakOnNonAscii)
			// We have been told to break on the first non-ascii character.
			// We haven't got any non-ascii character yet, but then we
			// have not checked all of the available bytes either. So we
			// cannot say for sure that this input stream is ALL_ASCII,
			// and hence we must play safe and return MOSTLY_NONASCII

			return MOSTLY_NONASCII;

		if (non_ascii == 0) { // no non-us-ascii characters so far
			// If we're looking at non-text data, and we saw CR without LF
			// or vice versa, consider this mostly non-ASCII so that it
			// will be base64 encoded (since the quoted-printable encoder
			// doesn't encode this case properly).
			if (badEOL)
				return MOSTLY_NONASCII;
			// if we've seen a long line, we degrade to mostly ascii
			else if (longLine)
				return MOSTLY_ASCII;
			else
				return ALL_ASCII;
		}
		if (ascii > non_ascii) // mostly ascii
			return MOSTLY_ASCII;
		return MOSTLY_NONASCII;
	}

	static final boolean nonascii(int b) {
		return b >= 0177 || (b < 040 && b != '\r' && b != '\n' && b != '\t');
	}
}

/**
 * An OutputStream that determines whether the data written to it is all ASCII,
 * mostly ASCII, or mostly non-ASCII.
 */
class AsciiOutputStream extends OutputStream {
	private boolean breakOnNonAscii;
	private int ascii = 0, non_ascii = 0;
	private int linelen = 0;
	private boolean longLine = false;
	private boolean badEOL = false;
	private boolean checkEOL = false;
	private int lastb = 0;
	private int ret = 0;

	public AsciiOutputStream(boolean breakOnNonAscii, boolean encodeEolStrict) {
		this.breakOnNonAscii = breakOnNonAscii;
		checkEOL = encodeEolStrict && breakOnNonAscii;
	}

	public void write(int b) throws IOException {
		check(b);
	}

	public void write(byte b[]) throws IOException {
		write(b, 0, b.length);
	}

	public void write(byte b[], int off, int len) throws IOException {
		len += off;
		for (int i = off; i < len; i++)
			check(b[i]);
	}

	private final void check(int b) throws IOException {
		b &= 0xff;
		if (checkEOL && ((lastb == '\r' && b != '\n') || (lastb != '\r' && b == '\n')))
			badEOL = true;
		if (b == '\r' || b == '\n')
			linelen = 0;
		else {
			linelen++;
			if (linelen > 998) // 1000 - CRLF
				longLine = true;
		}
		if (MIMEUtil.nonascii(b)) { // non-ascii
			non_ascii++;
			if (breakOnNonAscii) { // we are done
				ret = MIMEUtil.MOSTLY_NONASCII;
				throw new EOFException();
			}
		} else
			ascii++;
		lastb = b;
	}

	/**
	 * Return ASCII-ness of data stream.
	 */
	public int getAscii() {
		if (ret != 0)
			return ret;
		// If we're looking at non-text data, and we saw CR without LF
		// or vice versa, consider this mostly non-ASCII so that it
		// will be base64 encoded (since the quoted-printable encoder
		// doesn't encode this case properly).
		if (badEOL)
			return MIMEUtil.MOSTLY_NONASCII;
		else if (non_ascii == 0) { // no non-us-ascii characters so far
			// if we've seen a long line, we degrade to mostly ascii
			if (longLine)
				return MIMEUtil.MOSTLY_ASCII;
			else
				return MIMEUtil.ALL_ASCII;
		}
		if (ascii > non_ascii) // mostly ascii
			return MIMEUtil.MOSTLY_ASCII;
		return MIMEUtil.MOSTLY_NONASCII;
	}
}
