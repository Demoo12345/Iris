package net.coderbot.iris.shaderpack;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;

import net.coderbot.iris.Iris;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.rendertarget.CustomNoiseTexture;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.InvalidIdentifierException;
import net.minecraft.util.Util;
import net.minecraft.util.registry.BuiltinRegistries;
import net.minecraft.util.registry.DynamicRegistryManager;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.jetbrains.annotations.Nullable;

public class ShaderPack {
	private final ProgramSet base;
	@Nullable
	private final ProgramSet overworld;
	private final ProgramSet nether;
	private final ProgramSet end;

	private final IdMap idMap;
	private final Map<String, Map<String, String>> langMap;
	private final CustomTexture customNoiseTexture;
	private final ShaderPackConfig config;
	private final ShaderProperties shaderProperties;

	public ShaderPack(Path root) throws IOException {
		// A null path is not allowed.
		Objects.requireNonNull(root);

		this.shaderProperties = loadProperties(root, "shaders.properties")
			.map(ShaderProperties::new)
			.orElseGet(ShaderProperties::empty);
		this.config = new ShaderPackConfig(Iris.getIrisConfig().getShaderPackName().orElse(""));
		this.config.load();

		this.base = new ProgramSet(root, root, this);
		this.overworld = loadOverrides(root, "world0", this);
		this.nether = loadOverrides(root, "world-1", this);
		this.end = loadOverrides(root, "world1", this);

		this.idMap = new IdMap(root);
		this.langMap = parseLangEntries(root);

		customNoiseTexture = shaderProperties.getNoiseTexturePath().map(path -> {
			try {
				// TODO: Make sure the resulting path is within the shaderpack?
				byte[] content = Files.readAllBytes(root.resolve(path));

				// TODO: Read the blur / clamp data from the shaderpack...
				return new CustomTexture(content, true, false);
			} catch (IOException e) {
				Iris.logger.error("Unable to read the custom noise texture at " + path);

				return null;
			}
		}).orElse(null);
		this.config.save();
	}

	@Nullable
	private static ProgramSet loadOverrides(Path root, String subfolder, ShaderPack pack) throws IOException {
		if (root == null) {
			return new ProgramSet(null, null, pack);
		}

		Path sub = root.resolve(subfolder);

		if (Files.exists(sub)) {
			return new ProgramSet(sub, root, pack);
		}

		return null;
	}

	// TODO: Copy-paste from IdMap, find a way to deduplicate this
	private static Optional<Properties> loadProperties(Path shaderPath, String name) {
		Properties properties = new Properties();

		if (shaderPath == null) return Optional.empty();

		try {
			// NB: shaders.properties is specified to be encoded with ISO-8859-1 by OptiFine,
			//     so we don't need to do the UTF-8 workaround here.
			properties.load(Files.newInputStream(shaderPath.resolve(name)));
		} catch (IOException e) {
			Iris.logger.debug("An " + name + " file was not found in the current shaderpack");

			return Optional.empty();
		}

		return Optional.of(properties);
	}

	public ProgramSet getProgramSet(DimensionId dimension) {
		ProgramSet overrides;

		switch (dimension) {
			case OVERWORLD:
				overrides = overworld;
				break;
			case NETHER:
				overrides = nether;
				break;
			case END:
				overrides = end;
				break;
			default:
				throw new IllegalArgumentException("Unknown dimension " + dimension);
		}

		return ProgramSet.merged(base, overrides);
	}

	public IdMap getIdMap() {
		return idMap;
	}

	public Map<String, Map<String, String>> getLangMap() {
		return langMap;
	}

	public Optional<CustomTexture> getCustomNoiseTexture() {
		return Optional.ofNullable(customNoiseTexture);
	}

	public ShaderProperties getShaderProperties() {
		return shaderProperties;
	}

	public ShaderPackConfig getConfig() {
		return config;
	}

	private Map<String, Map<String, String>> parseLangEntries(Path root) throws IOException {
		if (root == null) return new HashMap<>();

		Path langFolderPath = root.resolve("lang");
		Map<String, Map<String, String>> allLanguagesMap = new HashMap<>();

		if (!Files.exists(langFolderPath)) {
			return allLanguagesMap;
		}
		// We are using a max depth of one to ensure we only get the surface level *files* without going deeper
		// we also want to avoid any directories while filtering
		// Basically, we want the immediate files nested in the path for the langFolder
		// There is also Files.list which can be used for similar behavior
		Files.walk(langFolderPath, 1).filter(path -> !Files.isDirectory(path)).forEach(path -> {

			Map<String, String> currentLanguageMap = new HashMap<>();
			//some shaderpacks use optifines file name coding which is different than minecraft's.
			//An example of this is using "en_US.lang" compared to "en_us.json"
			//also note that optifine uses a property scheme for loading language entries to keep parity with other optifine features
			String currentFileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
			String currentLangCode = currentFileName.substring(0, currentFileName.lastIndexOf("."));
			Properties properties = new Properties();

			try {
				// Use InputStreamReader to avoid the default charset of ISO-8859-1.
				// This is needed since shader language files are specified to be in UTF-8.
				properties.load(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8));
			} catch (IOException e) {
				Iris.logger.error("Error while parsing languages for shaderpacks! Expected File Path: {}", path);
				Iris.logger.catching(Level.ERROR, e);
			}

			properties.forEach((key, value) -> currentLanguageMap.put(key.toString(), value.toString()));
			allLanguagesMap.put(currentLangCode, currentLanguageMap);
		});

		return allLanguagesMap;
	}
}
