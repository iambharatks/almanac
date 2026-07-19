/**
 * Definition for a binary tree node.
 * struct TreeNode {
 *     int val;
 *     TreeNode *left;
 *     TreeNode *right;
 *     TreeNode() : val(0), left(nullptr), right(nullptr) {}
 *     TreeNode(int x) : val(x), left(nullptr), right(nullptr) {}
 *     TreeNode(int x, TreeNode *left, TreeNode *right) : val(x), left(left), right(right) {}
 * };
 */
class Solution {
public:
    bool helper(TreeNode *root, long long left, long long right){
        if(!root) return true;
        if(root->val <= left) return false;
        if(root->val >= right) return false;
        return helper(root->left,left,root->val) && helper(root->right,root->val,right); 
    }
    bool isValidBST(TreeNode* root) {
        return helper(root,LONG_LONG_MIN,LONG_LONG_MAX);
    }
};