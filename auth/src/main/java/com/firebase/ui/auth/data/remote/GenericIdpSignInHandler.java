package com.firebase.ui.auth.data.remote;

import com.firebase.ui.auth.ErrorCodes;
import com.firebase.ui.auth.FirebaseAuthAnonymousUpgradeException;
import com.firebase.ui.auth.FirebaseUiException;
import com.firebase.ui.auth.FirebaseUiUserCollisionException;
import com.firebase.ui.auth.IdpResponse;
import com.firebase.ui.auth.data.model.FlowParameters;

import android.app.Application;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.VisibleForTesting;

import com.firebase.ui.auth.AuthUI;

import com.firebase.ui.auth.data.model.Resource;
import com.firebase.ui.auth.data.model.User;
import com.firebase.ui.auth.data.model.UserCancellationException;
import com.firebase.ui.auth.ui.HelperActivityBase;
import com.firebase.ui.auth.ui.idp.WelcomeBackIdpPrompt;
import com.firebase.ui.auth.util.ExtraConstants;
import com.firebase.ui.auth.util.data.AuthOperationManager;
import com.firebase.ui.auth.util.data.ProviderUtils;
import com.firebase.ui.auth.viewmodel.ProviderSignInBase;
import com.firebase.ui.auth.viewmodel.RequestCodes;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.AuthResult;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthUserCollisionException;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.OAuthProvider;

import java.util.List;
import java.util.Map;

@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public class GenericIdpSignInHandler extends ProviderSignInBase<AuthUI.IdpConfig> {

    public GenericIdpSignInHandler(Application application) {
        super(application);
    }

    @Override
    public void startSignIn(@NonNull HelperActivityBase activity) {
        setResult(Resource.<IdpResponse>forLoading());
        start(FirebaseAuth.getInstance(), activity, getArguments().getProviderId());
    }

    public void startSignIn(@NonNull FirebaseAuth auth,
                            @NonNull HelperActivityBase activity,
                            @NonNull String providerId) {
        start(auth, activity, providerId);
    }

    public void start(@NonNull FirebaseAuth auth,
                      @NonNull HelperActivityBase activity,
                      @NonNull final String providerId) {
        setResult(Resource.<IdpResponse>forLoading());

        FlowParameters flowParameters = activity.getFlowParams();
        OAuthProvider provider = buildOAuthProvider(providerId);

        if (flowParameters != null
                && AuthOperationManager.getInstance().canUpgradeAnonymous(auth, flowParameters)) {
            if (activity instanceof WelcomeBackIdpPrompt) {
                handleAnonymousUpgradeLinkingFlow(activity, provider, flowParameters);
            } else {
                handleAnonymousUpgradeFlow(auth, activity, provider, flowParameters);
            }
            return;
        }

        handleNormalSignInFlow(auth, activity, provider);
    }

    private OAuthProvider buildOAuthProvider(String providerId) {
        OAuthProvider.Builder providerBuilder =
                OAuthProvider.newBuilder(providerId);

        List<String> scopes =
                getArguments().getParams().getStringArrayList(ExtraConstants.GENERIC_OAUTH_SCOPES);
        Map<String, String> customParams =
                getArguments().getParams()
                        .getParcelable(ExtraConstants.GENERIC_OAUTH_CUSTOM_PARAMETERS);

        if (scopes != null) {
            providerBuilder.setScopes(scopes);
        }
        if (customParams != null) {
            providerBuilder.addCustomParameters(customParams);
        }

        return providerBuilder.build();
    }

    private void handleNormalSignInFlow(final FirebaseAuth auth,
                                        final HelperActivityBase activity,
                                        final OAuthProvider provider) {
        auth.startActivityForSignInWithProvider(activity, provider)
                .addOnSuccessListener(
                        new OnSuccessListener<AuthResult>() {
                            @Override
                            public void onSuccess(@NonNull AuthResult authResult) {
                                FirebaseUser user = authResult.getUser();
                                IdpResponse idpResponse = new IdpResponse.Builder(
                                        new User.Builder(provider.getProviderId(), user.getEmail())
                                                .setName(user.getDisplayName())
                                                .setPhotoUri(user.getPhotoUrl())
                                                .build())
                                        .build();
                                setResult(Resource.<IdpResponse>forSuccess(idpResponse));
                            }
                        })
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception e) {
                                if (e instanceof FirebaseAuthUserCollisionException) {
                                    FirebaseAuthUserCollisionException collisionException =
                                            (FirebaseAuthUserCollisionException) e;

                                    setResult(Resource.<IdpResponse>forFailure(
                                            new FirebaseUiUserCollisionException(
                                                    ErrorCodes.ERROR_GENERIC_IDP_RECOVERABLE_ERROR,
                                                    "Recoverable error.",
                                                    provider.getProviderId(),
                                                    collisionException.getEmail(),
                                                    collisionException.getUpdatedCredential())));
                                } else {
                                    setResult(Resource.<IdpResponse>forFailure(e));
                                }
                            }
                        });

    }


    private void handleAnonymousUpgradeFlow(final FirebaseAuth auth,
                                            final HelperActivityBase activity,
                                            final OAuthProvider provider,
                                            final FlowParameters flowParameters) {
        auth.getCurrentUser()
                .startActivityForLinkWithProvider(activity, provider)
                .addOnSuccessListener(
                        new OnSuccessListener<AuthResult>() {
                            @Override
                            public void onSuccess(@NonNull AuthResult authResult) {
                                FirebaseUser user = authResult.getUser();
                                IdpResponse idpResponse = new IdpResponse.Builder(
                                        new User.Builder(provider.getProviderId(), user.getEmail())
                                                .setName(user.getDisplayName())
                                                .setPhotoUri(user.getPhotoUrl())
                                                .build())
                                        .build();
                                setResult(Resource.<IdpResponse>forSuccess(idpResponse));
                            }
                        })
                .addOnFailureListener(
                        new OnFailureListener() {
                            @Override
                            public void onFailure(@NonNull Exception e) {
                                if (e instanceof FirebaseAuthUserCollisionException) {

                                    final AuthCredential credential =
                                            ((FirebaseAuthUserCollisionException) e)
                                                    .getUpdatedCredential();
                                    final String email =
                                            ((FirebaseAuthUserCollisionException) e).getEmail();

                                    // Case 1: Anonymous user trying to link with an existing user
                                    // Case 2: Anonymous user trying to link with a provider keyed
                                    // by an email that already belongs to an existing account
                                    // (linking flow)
                                    ProviderUtils.fetchSortedProviders(auth, flowParameters, email)
                                            .addOnSuccessListener(new OnSuccessListener<List<String>>() {
                                                @Override
                                                public void onSuccess(List<String> providers) {
                                                    if (providers.isEmpty()) {
                                                        setResult(Resource.<IdpResponse>forFailure(
                                                                new FirebaseUiException(
                                                                        ErrorCodes.DEVELOPER_ERROR,
                                                                        "No supported providers.")));
                                                        return;
                                                    }

                                                    if (providers.contains(provider.getProviderId())) {
                                                        // Case 1
                                                        handleMergeFailure(credential);
                                                    } else {
                                                        // Case 2 - linking flow to be handled by
                                                        // SocialProviderResponseHandler
                                                        setResult(Resource.<IdpResponse>forFailure(
                                                                new FirebaseUiUserCollisionException(
                                                                        ErrorCodes.ERROR_GENERIC_IDP_RECOVERABLE_ERROR,
                                                                        "Recoverable error.",
                                                                        provider.getProviderId(),
                                                                        email,
                                                                        credential)));
                                                    }
                                                }
                                            });

                                } else {
                                    setResult(Resource.<IdpResponse>forFailure(e));
                                }
                            }
                        });
    }

    private void handleAnonymousUpgradeLinkingFlow(final HelperActivityBase activity,
                                                   final OAuthProvider provider,
                                                   final FlowParameters flowParameters) {

        AuthOperationManager.getInstance().safeGenericIdpSignIn(activity, provider, flowParameters)
                .addOnSuccessListener(new OnSuccessListener<AuthResult>() {
                    @Override
                    public void onSuccess(AuthResult authResult) {
                        IdpResponse response = new IdpResponse.Builder(
                                new User.Builder(
                                        provider.getProviderId(), authResult.getUser().getEmail())
                                        .setName(authResult.getUser().getDisplayName())
                                        .setPhotoUri(authResult.getUser().getPhotoUrl())
                                        .build())
                                .setPendingCredential(authResult.getCredential())
                                .build();
                        setResult(Resource.<IdpResponse>forSuccess(response));
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(@NonNull Exception e) {
                        setResult(Resource.<IdpResponse>forFailure(e));
                    }
                });

    }


    private void handleMergeFailure(AuthCredential credential) {
        IdpResponse failureResponse = new IdpResponse.Builder()
                .setPendingCredential(credential).build();
        setResult(Resource.<IdpResponse>forFailure(new FirebaseAuthAnonymousUpgradeException(
                ErrorCodes.ANONYMOUS_UPGRADE_MERGE_CONFLICT,
                failureResponse)));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
         if (requestCode == RequestCodes.GENERIC_IDP_SIGN_IN_FLOW) {
            IdpResponse response = IdpResponse.fromResultIntent(data);
            if (response == null) {
                setResult(Resource.<IdpResponse>forFailure(new UserCancellationException()));
            } else {
                setResult(Resource.forSuccess(response));
            }
        }
    }

    @VisibleForTesting
    public void initializeForTesting(AuthUI.IdpConfig idpConfig) {
        setArguments(idpConfig);
    }
}