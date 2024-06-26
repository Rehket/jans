---
# generated by https://github.com/hashicorp/terraform-plugin-docs
page_title: "jans_asset Resource - terraform-provider-jans"
subcategory: ""
description: |-
  Resource for managing Janssen assets.
---

# jans_asset (Resource)

Resource for managing Janssen assets.



<!-- schema generated by tfplugindocs -->
## Schema

### Required

- `asset` (String) The asset file.

### Optional

- `base_dn` (String) The base DN of the document.
- `creation_date` (String) The creation date of the document.
- `description` (String) The description of the document.
- `display_name` (String) The display name of the document.
- `dn` (String) The DN of the document.
- `document` (String) The document.
- `inum` (String) The inum of the document.
- `jans_alias` (String) The Jans alias of the document.
- `jans_enabled` (Boolean) The Jans enabled of the document.
- `jans_file_path` (String) The Jans file path of the document.
- `jans_level` (String) The Jans level of the document.
- `jans_module_property` (List of String) The Jans module property of the document.
- `jans_revision` (String) The Jans revision of the document.
- `selected` (Boolean) The selected of the document.

### Read-Only

- `id` (String) The ID of this resource.
