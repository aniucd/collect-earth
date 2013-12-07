package org.openforis.collect.earth.app.service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.openforis.collect.earth.app.EarthConstants;
import org.openforis.collect.earth.app.service.LocalPropertiesService.EarthProperty;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.firefox.FirefoxBinary;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.UnreachableBrowserException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

@Component
public class BrowserService {

	static class TrustAllCertificates implements X509TrustManager {
		@Override
		public void checkClientTrusted(X509Certificate[] cert, String s) throws CertificateException {
		}

		@Override
		public void checkServerTrusted(X509Certificate[] cert, String s) throws CertificateException {
		}

		@Override
		public X509Certificate[] getAcceptedIssuers() {
			// TODO Auto-generated method stub
			return null;
		}

	}

	private static final String NEW = "=new ";
	private static final String PROTOTYPE = ".prototype";
	private static final String ZOOM_10 = "zoom,10)";
	private static final String WORKSPACE_EL = "(\"workspace-el\");";
	private static final String LAYER_CONTAINER_BODY = "layer-container-body";

	@Autowired
	protected LocalPropertiesService localPropertiesService;
	
	@Autowired
	BingMapService bingMapService;		

	private Vector<RemoteWebDriver> drivers = new Vector<RemoteWebDriver>();
	private final Logger logger = LoggerFactory.getLogger(BrowserService.class);
	private static final String KML_FOR_GEE_JS = "resources/javascrip_gee.fmt";
	private static final Configuration cfg = new Configuration();
	private static Template template;
	
	private RemoteWebDriver webDriverEE, webDriverBing, webDriverTimelapse;

	private static boolean hasCheckValidity = false;

	public BrowserService() {

		attachShutDownHook();
	}

	public void attachShutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			@Override
			public void run() {
				for (RemoteWebDriver driver : drivers) {
					try {
						driver.quit();
					} catch (Exception e) {
						logger.error("Error quitting the browser", e);
					}
				}
			}
		});
	}

	private RemoteWebDriver chooseDriver() {
		RemoteWebDriver driver = null;

		String chosenBrowser = localPropertiesService.getValue(EarthProperty.BROWSER_TO_USE);

		if (chosenBrowser != null && chosenBrowser.trim().toLowerCase().equals(EarthConstants.CHROME_BROWSER)) {

			driver = startChromeBrowser(driver);
		} else {

			driver = startFirefoxBrowser(driver);
		}

		return driver;

	}

	private String getCompleteGeeJS() throws IOException {
		BufferedReader in = null;
		StringBuffer jsResult = new StringBuffer();
		try {
			URL geeJsUrl = new URL(localPropertiesService.getValue(EarthProperty.GEE_JS_LIBRARY_URL));

			// Start Work-around so that we can connect to an HTTPS server without needing to include the certificate
			SSLContext ssl = SSLContext.getInstance("TLSv1");
			ssl.init(null, new TrustManager[] { new TrustAllCertificates() }, null);
			SSLSocketFactory factory = ssl.getSocketFactory();
			HttpsURLConnection connection = (HttpsURLConnection) geeJsUrl.openConnection();
			connection.setSSLSocketFactory(factory);

			connection.setHostnameVerifier(new HostnameVerifier() {
				@Override
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			});
			// End or work-around

			in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
			String inputLine;
			while ((inputLine = in.readLine()) != null) {
				jsResult.append(inputLine);
			}
			in.close();
		} catch (Exception e) {
			logger.error("Not possible to read URI " + localPropertiesService.getValue(EarthProperty.GEE_JS_LIBRARY_URL), e);
			return null;
		} finally {
			if (in != null) {
				in.close();
			}
		}

		return jsResult.toString();
	}

	private String getGEEJavascript(String[] latLong) {

		Map<String, String> data = new HashMap<String, String>();
		data.put("latitude", latLong[0]);
		data.put("longitude", latLong[1]);

		data.put(EarthProperty.GEE_FUNCTION_PICK.toString(), localPropertiesService.getValue(EarthProperty.GEE_FUNCTION_PICK));
		data.put(EarthProperty.GEE_ZOOM_OBJECT.toString(), localPropertiesService.getValue(EarthProperty.GEE_ZOOM_OBJECT));
		data.put(EarthProperty.GEE_ZOOM_METHOD.toString(), localPropertiesService.getValue(EarthProperty.GEE_ZOOM_METHOD));
		data.put(EarthProperty.GEE_INITIAL_ZOOM.toString(), localPropertiesService.getValue(EarthProperty.GEE_INITIAL_ZOOM));

		StringWriter fw = null;
		Writer out = null;
		try {
			if (template == null) {
				// Load template from source folder
				template = cfg.getTemplate(KML_FOR_GEE_JS);
			}
			// Console output
			fw = new StringWriter();
			out = new BufferedWriter(fw);

			// Add date to avoid caching
			template.process(data, out);

		} catch (TemplateException e) {
			logger.error("Error when generating the GEE javascript commands", e);
		} catch (IOException e) {
			logger.error("Error when reading/writing the template information", e);
		} finally {
			try {
				if (out != null) {
					out.flush();
				}
				if (fw != null) {
					fw.close();
				}
			} catch (IOException e) {
				logger.error("Error when closing the output streams", e);
			}
		}

		return fw != null ? fw.toString() : null;

	}

	private RemoteWebDriver initBrowser() {
		RemoteWebDriver driver = null;
		try {
			driver = chooseDriver();
			drivers.add(driver);
		} catch (Exception e) {
			logger.error("Problems starting chosen browser", e);
		}
		return driver;
	}

	private boolean isGEEValidJS(String geeJs, RemoteWebDriver driver) {

		boolean stillValid = false;

		try {

			geeJs = geeJs.substring(0, geeJs.indexOf("focusObject."));
			((JavascriptExecutor) driver).executeScript(geeJs);

			stillValid = true;
		} catch (Exception e) {
			logger.error("Error in the selenium driver", e);
		}

		return stillValid;
	}

	private boolean isIdPresent(String elementId, RemoteWebDriver driver) {
		boolean found = false;

		try {
			if (driver.findElementById(elementId).isDisplayed()) {
				found = true;
			}
			logger.debug("Found " + elementId);
		} catch (Exception e) {
			logger.debug("Not Found " + elementId);
		}

		return found;
	}

	private RemoteWebDriver loadLayers(String[] latLong, RemoteWebDriver driver) throws InterruptedException {

		if (driver != null) {
			if (!isIdPresent("workspace-el", driver)) {
				String[] layers = {
				// "http://earthengine.google.org/#detail/LANDSAT%2FL7_L1T_ANNUAL_GREENEST_TOA"
				// "http://earthengine.google.org/#detail/LANDSAT%2FL5_L1T_ANNUAL_GREENEST_TOA",
				"https://earthengine.google.org/#detail/LANDSAT%2FLC8_L1T_ANNUAL_GREENEST_TOA" };
				for (String urlForLayer : layers) {
					driver = navigateTo(urlForLayer, driver);
					if (waitFor("d_open_button", driver)) {
						driver.findElementById("d_open_button").click();
						waitFor("workspace-el", driver);
					}
				}
			}
			if (waitFor("workspace-el", driver)) {
				if (driver instanceof JavascriptExecutor) {
					try {
						String geeJs = getGEEJavascript(latLong);
						if (!hasCheckValidity) {
							try {
								if (!isGEEValidJS(geeJs, driver)) {
									refreshJSValues(geeJs, driver);
									geeJs = getGEEJavascript(latLong);
								}
							} catch (Exception e) {
								logger.error("Error checking the validity of the GEE js code", e);
							} finally {
								hasCheckValidity = true;
							}
						}
						((JavascriptExecutor) driver).executeScript(geeJs);
					} catch (Exception e) {
						logger.warn("Error in the selenium driver", e);
					}
					Thread.sleep(1000);
					List<WebElement> dataLayerVisibility = driver.findElementsByClassName("indicator");
					for (WebElement webElement : dataLayerVisibility) {
						if (webElement.isDisplayed()) {
							webElement.click();
							Thread.sleep(1000);
							webElement.click();
						}
					}
				}
			}
		}
		return driver;
	}

	public synchronized RemoteWebDriver navigateTo(String url, RemoteWebDriver driver) {

		if (driver == null) {
			driver = initBrowser();
		}
		if (driver != null) {
			try {
				driver.navigate().to(url);
			} catch (UnreachableBrowserException e) {
				// Browser closed, restart it!
				logger.error("Browser was closed, restaring it...");
				driver = initBrowser();
				navigateTo(url, driver);
			}
		}
		return driver;
	}

	public synchronized void openBingMaps(String coordinates) {

		if (webDriverBing == null) {
			webDriverBing = initBrowser();
		}

		if (localPropertiesService.isBingMapsSupported()) {

			final RemoteWebDriver driverCopy = webDriverBing;
			final String[] latLong = coordinates.split(",");
			Thread loadBingThread = new Thread() {
				@Override
				public void run() {

					try {
						webDriverBing = navigateTo(bingMapService.getTemporaryUrl(latLong) , driverCopy);
					} catch (Exception e) {
						logger.error("Problems loading Bing", e);
					}

				};
			};

			loadBingThread.start();
		}
	}
	
	public synchronized void openEarthEngine(String coordinates) {

		if (webDriverEE == null) {
			webDriverEE = initBrowser();
		}

		if (localPropertiesService.isEarthEngineSupported()) {

			final String[] latLong = coordinates.split(",");

			final RemoteWebDriver driverCopy = webDriverEE;
			Thread loadEEThread = new Thread() {
				@Override
				public void run() {
					try {
						webDriverEE = loadLayers(latLong, driverCopy);
					} catch (Exception e) {
						logger.error("Error when opening Earth Engine browser window", e);
					}
				};
			};
			loadEEThread.start();
		}
	}

	public synchronized void openTimelapse(final String coordinates) {

		if (webDriverTimelapse == null) {
			webDriverTimelapse = initBrowser();
		}

		if (localPropertiesService.isTimelapseSupported()) {

			final RemoteWebDriver driverCopy = webDriverTimelapse;
			Thread loadTimelapseThread = new Thread() {
				@Override
				public void run() {

					try {
						webDriverTimelapse = navigateTo("https://earthengine.google.org/#timelapse/v=" + coordinates +",10.812,latLng&t=0.08", driverCopy);
					} catch (Exception e) {
						logger.error("Problems loading Timelapse", e);
					}

				};
			};
			loadTimelapseThread.start();
		}
	};

	private void refreshJSValues(String geeJs, RemoteWebDriver driver) throws IOException {
		String jsGee = getCompleteGeeJS();
		if (jsGee != null && jsGee.length() > 0) {
			// try to find this pattern for the gee_js_pickFunction value ("workspace-el"); " var b=H("workspace-el"); "
			int indexWorkspaceEL = jsGee.indexOf(WORKSPACE_EL);
			int startEquals = 0;
			for (startEquals = indexWorkspaceEL; startEquals > indexWorkspaceEL - 20; startEquals--) {
				if (jsGee.charAt(startEquals) == '=') {
					break;
				}
			}
			String pickFunction = jsGee.substring(startEquals + 1, indexWorkspaceEL).trim();

			// try to find this pattern for the gee_js_zoom_object value Mt G(g,"layer-container-body");a.na=new Mt(c,b);f.V(a.na,
			int indexLayer = jsGee.indexOf(LAYER_CONTAINER_BODY, startEquals);
			int indexEqual = jsGee.indexOf(NEW, indexLayer);
			int indexParenthesis = jsGee.indexOf('(', indexEqual);

			String zoomObject = jsGee.substring(indexEqual + NEW.length(), indexParenthesis).trim();

			// try to find this pattern for the gee_js_zoom_function value Mt Mt.prototype.I=function(a){if(ja(a)){Qt(this);var b=a.viewport;if(b){var
			// c=parseInt(b.zoom,10),d=parseFloat(b.lat),
			int indexZoom10 = jsGee.indexOf(ZOOM_10);
			int startPrototype = jsGee.indexOf(zoomObject + PROTOTYPE, indexZoom10 - 200);

			String zoomFunction = jsGee.substring(startPrototype + zoomObject.length() + PROTOTYPE.length() + 1, jsGee.indexOf('=', startPrototype))
					.trim();

			localPropertiesService.setValue(EarthProperty.GEE_FUNCTION_PICK, pickFunction);
			localPropertiesService.setValue(EarthProperty.GEE_ZOOM_METHOD, zoomFunction);
			localPropertiesService.setValue(EarthProperty.GEE_ZOOM_OBJECT, zoomObject);
		}

	}

	private RemoteWebDriver startChromeBrowser(RemoteWebDriver driver) {

		Properties props = System.getProperties();
		if (props.getProperty("webdriver.chrome.driver") == null) {
			props.setProperty("webdriver.chrome.driver", "resources/chromedriver.exe");
		}

		String chromeBinaryPath = localPropertiesService.getValue(EarthProperty.CHROME_BINARY_PATH);
		if (chromeBinaryPath != null && chromeBinaryPath.trim().length() > 0) {
			try {
				DesiredCapabilities capabilities = DesiredCapabilities.chrome();
				capabilities.setCapability("chrome.binary", chromeBinaryPath);
				driver = new ChromeDriver(capabilities);
			} catch (WebDriverException e) {
				logger.error("The chrome executable chrome.exe cannot be found, please edit earth.properties and correct the chrome.exe location at "
						+ EarthProperty.FIREFOX_BINARY_PATH + " pointing to the full path to chrome.exe", e);
			}
		} else {
			try {
				driver = new ChromeDriver();
			} catch (WebDriverException e) {
				logger.error("The chrome executable chrome.exe cannot be found, please edit earth.properties and add the chrome.exe location in "
						+ EarthProperty.FIREFOX_BINARY_PATH + " pointing to the full path to chrome.exe", e);
			}
		}
		return driver;
	}

	private RemoteWebDriver startFirefoxBrowser(RemoteWebDriver driver) {
		FirefoxProfile ffprofile = new FirefoxProfile();
		FirefoxBinary firefoxBinary = null;

		String firefoxBinaryPath = localPropertiesService.getValue(EarthProperty.FIREFOX_BINARY_PATH);

		if (firefoxBinaryPath != null && firefoxBinaryPath.trim().length() > 0) {
			try {
				firefoxBinary = new FirefoxBinary(new File(firefoxBinaryPath));
				driver = new FirefoxDriver(firefoxBinary, ffprofile);
			} catch (WebDriverException e) {
				logger.error(
						"The firefox executable firefox.exe cannot be found, please edit earth.properties and correct the firefox.exe location at "
								+ EarthProperty.FIREFOX_BINARY_PATH + " pointing to the full path to firefox.exe", e);
			}
		} else {
			// Try with default Firefox executable
			try {
				firefoxBinary = new FirefoxBinary();
				driver = new FirefoxDriver(firefoxBinary, ffprofile);
			} catch (WebDriverException e) {
				logger.error("The firefox executable firefox.exe cannot be found, please edit earth.properties and add a line with the property "
						+ EarthProperty.FIREFOX_BINARY_PATH + " pointing to the full path to firefox.exe", e);
			}
		}
		return driver;
	}

	private boolean waitFor(String elementId, RemoteWebDriver driver) {
		int i = 0;
		while (!isIdPresent(elementId, driver)) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				return false;
			}
			i++;
			if (i > 30) {
				return false;
			}
		}
		return true;
	}
}