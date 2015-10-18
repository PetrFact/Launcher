package launchserver;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

import launcher.Launcher;
import launcher.LauncherAPI;
import launcher.client.ClientLauncher;
import launcher.client.ClientProfile;
import launcher.hasher.HashedDir;
import launcher.helper.CommonHelper;
import launcher.helper.IOHelper;
import launcher.helper.JVMHelper;
import launcher.helper.LogHelper;
import launcher.helper.SecurityHelper;
import launcher.helper.VerifyHelper;
import launcher.serialize.config.ConfigObject;
import launcher.serialize.config.TextConfigReader;
import launcher.serialize.config.TextConfigWriter;
import launcher.serialize.config.entry.BlockConfigEntry;
import launcher.serialize.config.entry.BooleanConfigEntry;
import launcher.serialize.config.entry.IntegerConfigEntry;
import launcher.serialize.config.entry.StringConfigEntry;
import launcher.serialize.signed.SignedObjectHolder;
import launchserver.auth.AuthException;
import launchserver.auth.handler.AuthHandler;
import launchserver.auth.handler.CachedAuthHandler;
import launchserver.auth.handler.FileAuthHandler;
import launchserver.auth.provider.AuthProvider;
import launchserver.binary.EXEL4JLauncherBinary;
import launchserver.binary.EXELauncherBinary;
import launchserver.binary.JARLauncherBinary;
import launchserver.binary.LauncherBinary;
import launchserver.command.Command;
import launchserver.command.CommandException;
import launchserver.command.handler.CommandHandler;
import launchserver.command.handler.JLineCommandHandler;
import launchserver.command.handler.StdCommandHandler;
import launchserver.response.ServerSocketHandler;

public final class LaunchServer implements Runnable {
	// Constant paths
	@LauncherAPI public static final Path CONFIG_FILE = IOHelper.WORKING_DIR.resolve("LaunchServer.cfg");
	@LauncherAPI public static final Path PUBLIC_KEY_FILE = IOHelper.WORKING_DIR.resolve("public.key");
	@LauncherAPI public static final Path PRIVATE_KEY_FILE = IOHelper.WORKING_DIR.resolve("private.key");
	@LauncherAPI public static final Path UPDATES_DIR = IOHelper.WORKING_DIR.resolve("updates");
	@LauncherAPI public static final Path PROFILES_DIR = IOHelper.WORKING_DIR.resolve("profiles");

	// Launcher binary
	@LauncherAPI public final LauncherBinary launcherBinary = new JARLauncherBinary(this);
	private volatile LauncherBinary launcherEXEBinary;

	// Server
	@LauncherAPI public final CommandHandler commandHandler;
	@LauncherAPI public final ServerSocketHandler serverSocketHandler;
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final ScriptEngine engine = CommonHelper.newScriptEngine();

	// Launcher config
	private volatile Config config;
	private volatile RSAPublicKey publicKey;
	private volatile RSAPrivateKey privateKey;

	// Updates and profiles
	private volatile List<SignedObjectHolder<ClientProfile>> profilesList;
	private volatile Map<String, SignedObjectHolder<HashedDir>> updatesDirMap;

	private LaunchServer() throws IOException, InvalidKeySpecException {
		setScriptBindings();

		// Set command handler
		CommandHandler localCommandHandler;
		try {
			Class.forName("jline.Terminal");

			// JLine2 available
			localCommandHandler = new JLineCommandHandler(this);
			LogHelper.info("JLine2 terminal enabled");
		} catch (ClassNotFoundException ignored) {
			localCommandHandler = new StdCommandHandler(this);
			LogHelper.warning("JLine2 isn't in classpath, using std");
		}
		commandHandler = localCommandHandler;

		// Setup
		reloadKeyPair();
		reloadConfig();
		hashLauncherBinaries();

		// Hash updates dir
		if (!IOHelper.isDir(UPDATES_DIR)) {
			Files.createDirectory(UPDATES_DIR);
		}
		hashUpdatesDir(null);

		// Hash profiles dir
		if (!IOHelper.isDir(PROFILES_DIR)) {
			Files.createDirectory(PROFILES_DIR);
		}
		hashProfilesDir();

		// Set server socket thread
		serverSocketHandler = new ServerSocketHandler(this);
	}

	@Override
	public void run() {
		if (started.getAndSet(true)) {
			throw new IllegalStateException("LaunchServer has been already started");
		}

		// Load plugin script if exist
		Path scriptFile = IOHelper.WORKING_DIR.resolve("plugin.js");
		if (IOHelper.isFile(scriptFile)) {
			LogHelper.info("Loading plugin.js script");
			try {
				loadScript(IOHelper.toURL(scriptFile));
			} catch (Throwable exc) {
				LogHelper.error(exc);
			}
		}

		// Add shutdown hook, then start LaunchServer
		JVMHelper.RUNTIME.addShutdownHook(CommonHelper.newThread(null, false, this::shutdownHook));
		CommonHelper.newThread("Command Thread", true, commandHandler).start();
		rebindServerSocket();
	}

	@LauncherAPI
	public void buildLauncherBinaries() throws IOException {
		launcherBinary.build();
		launcherEXEBinary.build();
	}

	@LauncherAPI
	public Config getConfig() {
		return config;
	}

	@LauncherAPI
	public LauncherBinary getEXEBinary() {
		return launcherEXEBinary;
	}

	@LauncherAPI
	public RSAPrivateKey getPrivateKey() {
		return privateKey;
	}

	@LauncherAPI
	@SuppressWarnings("ReturnOfCollectionOrArrayField")
	public Collection<SignedObjectHolder<ClientProfile>> getProfiles() {
		return profilesList;
	}

	@LauncherAPI
	public RSAPublicKey getPublicKey() {
		return publicKey;
	}

	@LauncherAPI
	public SignedObjectHolder<HashedDir> getUpdateDir(String name) {
		return updatesDirMap.get(name);
	}

	@LauncherAPI
	public void hashLauncherBinaries() throws IOException {
		LogHelper.info("Hashing launcher binaries");

		// Hash launcher binary
		LogHelper.subInfo("Hashing launcher binary file");
		if (!launcherBinary.hash()) {
			LogHelper.subWarning("Missing launcher binary file");
		}

		// Hash launcher EXE binary
		LogHelper.subInfo("Hashing launcher EXE binary file");
		if (!launcherEXEBinary.hash()) {
			LogHelper.subWarning("Missing launcher EXE binary file");
		}
	}

	@LauncherAPI
	public void hashProfilesDir() throws IOException {
		LogHelper.info("Hashing profiles dir");
		List<SignedObjectHolder<ClientProfile>> newProfies = new LinkedList<>();
		IOHelper.walk(PROFILES_DIR, new ProfilesFileVisitor(newProfies), false);

		// Sort and set new profiles
		Collections.sort(newProfies, (a, b) -> a.object.compareTo(b.object));
		profilesList = Collections.unmodifiableList(newProfies);
	}

	@LauncherAPI
	public void hashUpdatesDir(Collection<String> dirs) throws IOException {
		LogHelper.info("Hashing updates dir");
		Map<String, SignedObjectHolder<HashedDir>> newUpdatesDirMap = new HashMap<>(16);
		try (DirectoryStream<Path> dirStream = Files.newDirectoryStream(UPDATES_DIR)) {
			for (Path updateDir : dirStream) {
				if (Files.isHidden(updateDir)) {
					continue; // Skip hidden
				}

				// Resolve name and verify is dir
				String name = IOHelper.getFileName(updateDir);
				if (!IOHelper.isDir(updateDir)) {
					LogHelper.subWarning("Not update dir: '%s'", name);
					continue;
				}

				// Add from previous map (it's guaranteed to be non-null)
				if (dirs != null && !dirs.contains(name)) {
					SignedObjectHolder<HashedDir> hdir = updatesDirMap.get(name);
					if (hdir != null) {
						newUpdatesDirMap.put(name, hdir);
						continue;
					}
				}

				// Hash and sign update dir
				LogHelper.subInfo("Hashing '%s' update dir", name);
				HashedDir updateHDir = new HashedDir(updateDir, null);
				newUpdatesDirMap.put(name, new SignedObjectHolder<>(updateHDir, privateKey));
			}
		}
		updatesDirMap = Collections.unmodifiableMap(newUpdatesDirMap);
	}

	@LauncherAPI
	public Object loadScript(URL url) throws IOException, ScriptException {
		LogHelper.debug("Loading server script: '%s'", url);
		try (BufferedReader reader = IOHelper.newReader(url)) {
			return engine.eval(reader);
		}
	}

	@LauncherAPI
	public void rebindServerSocket() {
		serverSocketHandler.close();
		CommonHelper.newThread("Server Socket Thread", false, serverSocketHandler).start();
	}

	@LauncherAPI
	public void reloadConfig() throws IOException {
		Config oldConfig = config;

		// Create LaunchServer config if not exist
		Config newConfig;
		if (!IOHelper.isFile(CONFIG_FILE)) {
			LogHelper.info("Creating LaunchServer config");
			try (BufferedReader reader = IOHelper.newReader(IOHelper.getResourceURL("launchserver/defaults/config.cfg"))) {
				newConfig = new Config(TextConfigReader.read(reader, false));
			}

			// Set server address
			LogHelper.println("LaunchServer address: ");
			newConfig.setAddress(commandHandler.readLine());

			// Write LaunchServer config
			LogHelper.info("Writing LaunchServer config file");
			try (BufferedWriter writer = IOHelper.newWriter(CONFIG_FILE)) {
				TextConfigWriter.write(newConfig.block, writer, true);
			}
		}

		// Read LaunchServer config (also re-read after setup for RO)
		LogHelper.info("Reading LaunchServer config file");
		try (BufferedReader reader = IOHelper.newReader(CONFIG_FILE)) {
			newConfig = new Config(TextConfigReader.read(reader, true));
			if (oldConfig != null && !oldConfig.address.equals(newConfig.address)) {
				LogHelper.warning("To bind new address, use 'rebind' command");
			}
		}
		newConfig.verify();

		// Flush old config providers
		if (oldConfig != null) {
			// Flush auth handler
			try {
				config.authHandler.flush();
			} catch (IOException e) {
				LogHelper.error(e);
			}

			//  Flush auth provider
			try {
				config.authProvider.flush();
			} catch (IOException e) {
				LogHelper.error(e);
			}
		}

		// Apply changes
		config = newConfig;
		launcherEXEBinary = newConfig.launch4J ? new EXEL4JLauncherBinary(this) : new EXELauncherBinary(this);
	}

	@LauncherAPI
	public void reloadKeyPair() throws IOException, InvalidKeySpecException {
		RSAPublicKey newPublicKey;
		RSAPrivateKey newPrivateKey;
		if (IOHelper.isFile(PUBLIC_KEY_FILE) && IOHelper.isFile(PRIVATE_KEY_FILE)) {
			LogHelper.info("Reading RSA keypair");
			newPublicKey = SecurityHelper.toPublicRSAKey(IOHelper.read(PUBLIC_KEY_FILE));
			newPrivateKey = SecurityHelper.toPrivateRSAKey(IOHelper.read(PRIVATE_KEY_FILE));
			if (!newPublicKey.getModulus().equals(newPrivateKey.getModulus())) {
				throw new IOException("Private and public key modulus mismatch");
			}

			// Print keypair fingerprints
			CRC32 crc = new CRC32();
			crc.update(newPublicKey.getModulus().toByteArray());
			LogHelper.subInfo("Modulus CRC32: 0x%08x", crc.getValue());
		} else {
			LogHelper.info("Generating RSA keypair");
			KeyPair pair = SecurityHelper.genRSAKeyPair();
			newPublicKey = (RSAPublicKey) pair.getPublic();
			newPrivateKey = (RSAPrivateKey) pair.getPrivate();

			// Write key pair files
			LogHelper.info("Writing RSA keypair files");
			IOHelper.write(PUBLIC_KEY_FILE, newPublicKey.getEncoded());
			IOHelper.write(PRIVATE_KEY_FILE, newPrivateKey.getEncoded());
		}

		// Apply changes
		publicKey = newPublicKey;
		privateKey = newPrivateKey;
	}

	private void setScriptBindings() {
		LogHelper.info("Setting up server script engine bindings");
		Bindings bindings = engine.getBindings(ScriptContext.ENGINE_SCOPE);
		bindings.put("server", this);

		// Add launcher and launchserver class bindings
		Launcher.addLauncherClassBindings(bindings);
		addLaunchServerClassBindings(bindings);
	}

	private void shutdownHook() {
		serverSocketHandler.close();

		// Flush auth handler and provider
		try {
			config.authHandler.flush();
		} catch (IOException e) {
			LogHelper.error(e);
		}
		try {
			config.authProvider.flush();
		} catch (IOException e) {
			LogHelper.error(e);
		}

		// Print last message before death :(
		LogHelper.info("LaunchServer stopped");
	}

	public static void main(String... args) throws Throwable {
		JVMHelper.verifySystemProperties(LaunchServer.class);
		SecurityHelper.verifyCertificates(LaunchServer.class);
		LogHelper.addOutput(IOHelper.WORKING_DIR.resolve("LaunchServer.log"));
		LogHelper.printVersion("LaunchServer");

		// Start LaunchServer
		Instant start = Instant.now();
		try {
			new LaunchServer().run();
		} catch (Exception e) {
			LogHelper.error(e);
			return;
		}
		Instant end = Instant.now();
		LogHelper.debug("LaunchServer started in %dms", Duration.between(start, end).toMillis());
	}

	private static void addLaunchServerClassBindings(Map<String, Object> bindings) {
		bindings.put("LaunchServerClass", LaunchServer.class);

		// Set auth class bindings
		bindings.put("AuthHandlerClass", AuthHandler.class);
		bindings.put("FileAuthHandlerClass", FileAuthHandler.class);
		bindings.put("CachedAuthHandlerClass", CachedAuthHandler.class);
		bindings.put("AuthProviderClass", AuthProvider.class);
		bindings.put("DigestAuthProviderClass", AuthProvider.class);
		bindings.put("AuthExceptionClass", AuthException.class);

		// Set command class bindings
		bindings.put("CommandClass", Command.class);
		bindings.put("CommandHandlerClass", CommandHandler.class);
		bindings.put("CommandExceptionClass", CommandException.class);

		// Set response class bindings
		bindings.put("ResponseClass", Command.class);
		bindings.put("ResponseFactoryClass", CommandHandler.class);
	}

	private final class ProfilesFileVisitor extends SimpleFileVisitor<Path> {
		private final Collection<SignedObjectHolder<ClientProfile>> result;

		private ProfilesFileVisitor(Collection<SignedObjectHolder<ClientProfile>> result) {
			this.result = result;
		}

		@Override
		public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
			LogHelper.subInfo("Hashing '%s' profile", IOHelper.getFileName(file));

			// Read profile
			ClientProfile profile;
			try (BufferedReader reader = IOHelper.newReader(file)) {
				profile = new ClientProfile(TextConfigReader.read(reader, true));
			}

			// Add SIGNED profile to result list
			result.add(new SignedObjectHolder<>(profile, privateKey));
			return super.visitFile(file, attrs);
		}
	}

	public static final class Config extends ConfigObject {
		private static final UUID ZERO_UUID = new UUID(0, 0);

		// Instance
		@LauncherAPI public final int port;
		@LauncherAPI public final boolean metrics;
		private final StringConfigEntry address;
		private final String bindAddress;

		// Auth
		@LauncherAPI public final AuthHandler authHandler;
		@LauncherAPI public final AuthProvider authProvider;

		// EXE binary building
		@LauncherAPI public final boolean launch4J;

		// Skin system
		private final String skinsURL;
		private final String cloaksURL;

		private Config(BlockConfigEntry block) {
			super(block);
			address = block.getEntry("address", StringConfigEntry.class);
			port = block.getEntryValue("port", IntegerConfigEntry.class);
			bindAddress = block.hasEntry("bindAddress") ?
				block.getEntryValue("bindAddress", StringConfigEntry.class) : getAddress();
			metrics = block.getEntryValue("metrics", BooleanConfigEntry.class);

			// Skin system
			skinsURL = block.getEntryValue("skinsURL", StringConfigEntry.class);
			cloaksURL = block.getEntryValue("cloaksURL", StringConfigEntry.class);

			// Set auth handler and provider
			String authHandlerName = block.getEntryValue("authHandler", StringConfigEntry.class);
			authHandler = AuthHandler.newHandler(authHandlerName, block.getEntry("authHandlerConfig", BlockConfigEntry.class));
			String authProviderName = block.getEntryValue("authProvider", StringConfigEntry.class);
			authProvider = AuthProvider.newProvider(authProviderName, block.getEntry("authProviderConfig", BlockConfigEntry.class));

			// Set launch4J config
			launch4J = block.getEntryValue("launch4J", BooleanConfigEntry.class);
		}

		@Override
		public void verify() {
			VerifyHelper.verifyInt(port, VerifyHelper.range(0, 65535), "Illegal LaunchServer port: " + port);

			// Verify textures info
			String skinURL = getSkinURL("skinUsername", ZERO_UUID);
			if (skinURL != null) {
				IOHelper.verifyURL(skinURL);
			}
			String cloakURL = getCloakURL("cloakUsername", ZERO_UUID);
			if (cloakURL != null) {
				IOHelper.verifyURL(cloakURL);
			}

			// Verify auth handler and provider
			authHandler.verify();
			authProvider.verify();
		}

		@LauncherAPI
		public String getAddress() {
			return address.getValue();
		}

		@LauncherAPI
		public String getBindAddress() {
			return bindAddress;
		}

		@LauncherAPI
		public String getCloakURL(String username, UUID uuid) {
			return getTextureURL(cloaksURL, username, uuid);
		}

		@LauncherAPI
		public String getSkinURL(String username, UUID uuid) {
			return getTextureURL(skinsURL, username, uuid);
		}

		@LauncherAPI
		public SocketAddress getSocketAddress() {
			return new InetSocketAddress(bindAddress, port);
		}

		@LauncherAPI
		public void setAddress(String address) {
			this.address.setValue(address);
		}

		@LauncherAPI
		public static String getTextureURL(String url, String username, UUID uuid) {
			if (url.isEmpty()) {
				return null;
			}
			return CommonHelper.replace(url, "username", username, "uuid", uuid.toString(), "hash", ClientLauncher.toHash(uuid));
		}
	}
}