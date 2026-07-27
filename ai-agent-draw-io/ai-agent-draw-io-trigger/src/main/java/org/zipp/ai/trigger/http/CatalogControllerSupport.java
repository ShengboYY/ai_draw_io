package org.zipp.ai.trigger.http;

import org.zipp.ai.api.response.Response;
import org.zipp.ai.domain.account.model.valobj.ResolvedOwner;
import org.zipp.ai.domain.material.model.valobj.*;

import java.util.function.Supplier;

final class CatalogControllerSupport {
    private CatalogControllerSupport() {
    }

    static CatalogOwner requiredOwner(CurrentOwnerHttpResolver resolver) {
        ResolvedOwner owner = resolver.resolve(null)
                .orElseThrow(() -> new CatalogOperationException(CatalogErrorCode.REGISTERED_USER_REQUIRED));
        return new CatalogOwner(owner.getOwnerType(), owner.getOwnerId());
    }

    static <T> Response<T> execute(Supplier<T> action) {
        try {
            return Response.<T>builder().code("0000").info("success").data(action.get()).build();
        } catch (CatalogOperationException e) {
            return failure(e.code().name());
        } catch (IllegalArgumentException e) {
            return failure("CATALOG_REQUEST_INVALID");
        } catch (RuntimeException e) {
            return failure("TRANSIENT_DEPENDENCY");
        }
    }

    static <T> Response<T> failure(String code) {
        return Response.<T>builder().code(code).info("catalog request rejected").build();
    }
}
