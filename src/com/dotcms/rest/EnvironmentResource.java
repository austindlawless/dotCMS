package com.dotcms.rest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.servlet.http.HttpServletRequest;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.GET;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.Path;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.PathParam;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.Produces;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.core.CacheControl;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.core.Context;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.core.MediaType;
import com.dotcms.repackage.jersey_1_12.javax.ws.rs.core.Response;

import com.dotcms.publisher.environment.bean.Environment;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.DotStateException;
import com.dotmarketing.business.Role;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotSecurityException;
import com.liferay.portal.language.LanguageException;
import com.liferay.portal.model.User;


@Path("/environment")
public class EnvironmentResource extends WebResource {

	/**
	 * <p>Returns a JSON representation of the environments (with servers) that the Role with the given roleid can push to
	 * <br>Each Environment node contains: id, name.
	 *
	 * Usage: /loadenvironments/{roleid}
	 *
	 */

	@GET
	@Path("/loadenvironments/{params:.*}")
	@Produces("application/json")
	public Response loadEnvironments(@Context HttpServletRequest request, @PathParam("params") String params) throws DotStateException, DotDataException, DotSecurityException, LanguageException {

		InitDataObject initData = init(params, true, request, true);

        //Creating an utility response object
        ResourceResponse responseResource = new ResourceResponse( initData.getParamsMap() );

		String roleId = initData.getParamsMap().get("roleid");

		StringBuilder json = new StringBuilder();

		json.append("[");

		json.append("{id: '0', ");
		json.append("name: '' }, ");

		int environmentCounter = 0;

		Role role = APILocator.getRoleAPI().loadRoleById(roleId);
		User user = APILocator.getUserAPI().loadUserById(role.getRoleKey());
		boolean isAdmin = APILocator.getUserAPI().isCMSAdmin(user);

		List<Role> roles = APILocator.getRoleAPI().loadRolesForUser(user.getUserId(),true);
		Set<Environment> environments = new HashSet<Environment>();
		if(isAdmin){
			List<Environment> app = APILocator.getEnvironmentAPI().findEnvironmentsWithServers();
			for(Environment e:app)
				environments.add(e);
		}
		else
			for(Role r: roles)
				environments.addAll(APILocator.getEnvironmentAPI().findEnvironmentsByRole(r.getId()));

		for(Environment e : environments) {

			json.append("{id: '").append(e.getId()).append("', ");
			json.append("name: '").append(e.getName()).append("' } ");

			if(environmentCounter+1 < environments.size()) {
				json.append(", ");
			}

			environmentCounter++;
		}

		json.append("]");

		CacheControl cc = new CacheControl();
		cc.setNoCache(true);
		return Response.ok(json.toString(), MediaType.APPLICATION_JSON_TYPE).cacheControl(cc).build();

	}

}
