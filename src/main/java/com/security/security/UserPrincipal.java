package com.security.security;

import com.security.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class UserPrincipal implements UserDetails {

    private Long id;
    private String firstName;
    private String lastName;
    private String email;
    private String password;
    private Collection<? extends GrantedAuthority> authorities;
    private boolean enabled;
    private boolean accountNonExpired;
    private boolean accountNonLocked;
    private boolean credentialsNonExpired;

    public UserPrincipal(Long id, String firstName, String lastName, String email, String password,
            Collection<? extends GrantedAuthority> authorities, boolean enabled,
            boolean accountNonExpired, boolean accountNonLocked, boolean credentialsNonExpired) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.password = password;
        this.authorities = authorities;
        this.enabled = enabled;
        this.accountNonExpired = accountNonExpired;
        this.accountNonLocked = accountNonLocked;
        this.credentialsNonExpired = credentialsNonExpired;
    }

    public static UserPrincipal create(User user) {
        // Build authorities: include BOTH roles (e.g. ROLE_ADMIN) and
        // individual permissions (e.g. USER_CREATE, PRODUCT_READ).
        // LinkedHashSet preserves insertion order and avoids duplicates.
        Set<GrantedAuthority> authoritySet = new LinkedHashSet<>();

        user.getRoles().forEach(role -> {
            // Role-level authority (Spring hasRole() checks for these)
            authoritySet.add(new SimpleGrantedAuthority(role.getName()));

            // Permission-level authorities (Spring hasAuthority() checks for these)
            role.getPermissions()
                    .forEach(permission -> authoritySet.add(new SimpleGrantedAuthority(permission.getName())));
        });

        List<GrantedAuthority> authorities = new ArrayList<>(authoritySet);

        return new UserPrincipal(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPassword(),
                authorities,
                user.getEnabled(),
                user.getAccountNonExpired(),
                user.getAccountNonLocked(),
                user.getCredentialsNonExpired());
    }

    // UserDetails implementation
    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public boolean isAccountNonExpired() {
        return accountNonExpired;
    }

    @Override
    public boolean isAccountNonLocked() {
        return accountNonLocked;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return credentialsNonExpired;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    // Getters
    public Long getId() {
        return id;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }
}