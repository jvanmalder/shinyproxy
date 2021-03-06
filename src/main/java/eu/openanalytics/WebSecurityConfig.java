/**
 * Copyright 2016 Open Analytics, Belgium
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package eu.openanalytics;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.ldap.core.ContextSource;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.authentication.configurers.GlobalAuthenticationConfigurerAdapter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.builders.WebSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityConfigurerAdapter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.ldap.DefaultSpringSecurityContextSource;
import org.springframework.security.ldap.userdetails.DefaultLdapAuthoritiesPopulator;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import eu.openanalytics.components.LogoutHandler;
import eu.openanalytics.services.AppService;
import eu.openanalytics.services.AppService.ShinyApp;
import eu.openanalytics.services.UserService;

/**
 * @author Torkild U. Resheim, Itema AS
 */
@Configuration
@EnableWebSecurity
public class WebSecurityConfig extends WebSecurityConfigurerAdapter {

	@Inject
	LogoutHandler logoutHandler;

	@Inject
	Environment environment;		
	
	@Inject
	AppService appService;

	@Inject
	UserService userService;
	
	@Override
	public void configure(WebSecurity web) throws Exception {
		web
			.ignoring().antMatchers("/css/**").and()
			.ignoring().antMatchers("/webjars/**");
	}

	@Override
	protected void configure(HttpSecurity http) throws Exception {
		http
			// must disable or handle in proxy
			.csrf()
				.disable()
			// disable X-Frame-Options
			.headers()
				.frameOptions()
					.sameOrigin();

		if (hasAuth(environment)) {
			// Limit access to the app pages
			http.authorizeRequests().antMatchers("/login").permitAll();
			for (ShinyApp app: appService.getApps()) {
				String[] appRoles = appService.getAppRoles(app.getName());
				if (appRoles != null && appRoles.length > 0) http.authorizeRequests().antMatchers("/app/" + app.getName()).hasAnyRole(appRoles);
			}

			// Limit access to the admin pages
			http.authorizeRequests().antMatchers("/admin").hasAnyRole(userService.getAdminRoles());
			
			// All other pages are available to authenticated users
			http.authorizeRequests().anyRequest().fullyAuthenticated();

			http
				.formLogin()
					.loginPage("/login")
					.and()
				.logout()
					.logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
					.logoutSuccessHandler(logoutHandler)
					.logoutSuccessUrl("/login");
		}
	}

	private static boolean hasAuth(Environment env) {
		String auth = env.getProperty("shiny.proxy.authentication", "").toLowerCase();
		return (!auth.isEmpty() && !auth.equals("none"));
	}
	
	@Configuration
	protected static class AuthenticationConfiguration extends GlobalAuthenticationConfigurerAdapter {

		@Inject
		private Environment environment;		

		@Override
		public void init(AuthenticationManagerBuilder auth) throws Exception {
			if (!hasAuth(environment)) return;
			
			String[] userDnPatterns = { environment.getProperty("shiny.proxy.ldap.user-dn-pattern") };
			if (userDnPatterns[0] == null || userDnPatterns[0].isEmpty()) userDnPatterns = new String[0];

			String managerDn = environment.getProperty("shiny.proxy.ldap.manager-dn");
			if (managerDn != null && managerDn.isEmpty()) managerDn = null;
			
			// Manually instantiate contextSource so it can be passed into authoritiesPopulator below.
			String ldapUrl = environment.getProperty("shiny.proxy.ldap.url");
			DefaultSpringSecurityContextSource contextSource = new DefaultSpringSecurityContextSource(ldapUrl);
			if (managerDn != null) {
				contextSource.setUserDn(managerDn);
				contextSource.setPassword(environment.getProperty("shiny.proxy.ldap.manager-password"));
			}
			contextSource.afterPropertiesSet();

			// Manually instantiate authoritiesPopulator because it uses a customized class.
			CNLdapAuthoritiesPopulator authoritiesPopulator = new CNLdapAuthoritiesPopulator(
					contextSource,
					environment.getProperty("shiny.proxy.ldap.group-search-base", ""));
			authoritiesPopulator.setGroupRoleAttribute("cn");
			authoritiesPopulator.setGroupSearchFilter(environment.getProperty("shiny.proxy.ldap.group-search-filter", "(uniqueMember={0})"));

			auth
				.ldapAuthentication()
					.userDnPatterns(userDnPatterns)
					.userSearchBase(environment.getProperty("shiny.proxy.ldap.user-search-base", ""))
					.userSearchFilter(environment.getProperty("shiny.proxy.ldap.user-search-filter"))
					.ldapAuthoritiesPopulator(authoritiesPopulator)
					.contextSource(contextSource);
		}
	}

	private static class CNLdapAuthoritiesPopulator extends DefaultLdapAuthoritiesPopulator {

		private static final Log logger = LogFactory.getLog(DefaultLdapAuthoritiesPopulator.class);

		public CNLdapAuthoritiesPopulator(ContextSource contextSource, String groupSearchBase) {
			super(contextSource, groupSearchBase);
		}

		@Override
		public Set<GrantedAuthority> getGroupMembershipRoles(String userDn, String username) {
			if (getGroupSearchBase() == null) {
				return new HashSet<GrantedAuthority>();
			}

			Set<GrantedAuthority> authorities = new HashSet<GrantedAuthority>();

			if (logger.isDebugEnabled()) {
				logger.debug("Searching for roles for user '" + username + "', DN = " + "'"
						+ userDn + "', with filter " + getGroupSearchFilter()
						+ " in search base '" + getGroupSearchBase() + "'");
			}

			// Here's the modification: added {2}, which refers to the user cn if available.
			Set<String> userRoles = getLdapTemplate().searchForSingleAttributeValues(
					getGroupSearchBase(), getGroupSearchFilter(),
					new String[] { userDn, username, getCn(userDn) }, getGroupRoleAttribute());

			if (logger.isDebugEnabled()) {
				logger.debug("Roles from search: " + userRoles);
			}

			for (String role : userRoles) {

				if (isConvertToUpperCase()) {
					role = role.toUpperCase();
				}

				authorities.add(new SimpleGrantedAuthority(getRolePrefix() + role));
			}

			return authorities;
		}

		private String getCn(String dn) {
			try {
				LdapName ln = new LdapName(dn);
				for (Rdn rdn : ln.getRdns()) {
					if (rdn.getType().equalsIgnoreCase("CN")) {
						return rdn.getValue().toString();
					}
				}
			} catch (InvalidNameException e) {}
			return "";
		}
	}
}