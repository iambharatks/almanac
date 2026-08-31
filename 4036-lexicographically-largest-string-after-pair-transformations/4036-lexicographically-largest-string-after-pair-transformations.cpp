class Solution {
public:
    vector<string> largestString(vector<int>& nums) {
        char c = 'a';
        vector<string> res;
        for(int i : nums){
            char a = c;
            string ans = "";
            while(i){
                if(i&1){
                    ans.push_back(a);
                }
                i /= 2;
                a++;
                if(a == 'z') break;
            }
            while(i--){
                ans.push_back('z');
            }
            reverse(begin(ans),end(ans));
            res.push_back(ans);
        }
        return res;
    }
};