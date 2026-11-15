package com.security.dto.admin;

public class AssignableRoleDTO {
    private Long id;
    private String name;
    private String description;
    private Boolean systemRole;
    private Boolean assignable;
    private Integer level;
    private String scope;
    private String displayName;

    public AssignableRoleDTO() {}

    public AssignableRoleDTO(Long id, String name, String description, Boolean systemRole, Boolean assignable, Integer level, String scope, String displayName) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.systemRole = systemRole;
        this.assignable = assignable;
        this.level = level;
        this.scope = scope;
        this.displayName = displayName;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public Boolean getSystemRole() { return systemRole; }
    public void setSystemRole(Boolean systemRole) { this.systemRole = systemRole; }
    
    public Boolean getAssignable() { return assignable; }
    public void setAssignable(Boolean assignable) { this.assignable = assignable; }
    
    public Integer getLevel() { return level; }
    public void setLevel(Integer level) { this.level = level; }
    
    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }
    
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
}
