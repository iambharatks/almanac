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
    int res = 0;
public:
    pair<int,int> rec(TreeNode *root){
        if(!root) return {0,0};
        pair<int,int> left = rec(root->left);
        pair<int,int> right = rec(root->right);
        left.first += right.first + root->val;
        left.second += right.second + 1;
        if(left.second == 1 || left.first/left.second == root->val){
            res++;
        } 
        return left;
    }
    int averageOfSubtree(TreeNode* root) {
        rec(root);
        return res;
    }
};